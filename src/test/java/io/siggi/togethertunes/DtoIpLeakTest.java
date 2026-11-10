package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.siggi.simplewebsocket.SimpleWebsocket;
import io.siggi.simplewebsocket.WebSocketConnector;
import io.siggi.simplewebsocket.WebSocketMessage;
import io.siggi.simplewebsocket.WebsocketHeaders;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AdminActivityApi;
import io.siggi.togethertunes.auth.AdminApi;
import io.siggi.togethertunes.auth.AuditEntries;
import io.siggi.togethertunes.auth.AuthApi;
import io.siggi.togethertunes.auth.AuthRateLimits;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.IdentityTickets;
import io.siggi.togethertunes.auth.IpAccess;
import io.siggi.togethertunes.auth.MeApi;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Roles;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.auth.SessionsApi;
import io.siggi.togethertunes.auth.SetupApi;
import io.siggi.togethertunes.auth.Users;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.Json;
import io.siggi.togethertunes.http.PathPattern;
import io.siggi.togethertunes.http.RateLimiter;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.StaticFiles;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.library.Items;
import io.siggi.togethertunes.library.Library;
import io.siggi.togethertunes.library.Playlists;
import io.siggi.togethertunes.library.Shares;
import io.siggi.togethertunes.library.UserLibraries;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.lang.reflect.RecordComponent;
import java.net.InetAddress;
import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The DTO IP-leak test ({@code docs/auth.md#ip-address-policy}, invariant 5): nothing an actor
 * without {@code ip.view} receives contains anything that looks like an IP address.
 *
 * <ul>
 *   <li><b>Every DTO type</b>: every record in the main classes is classified below as a DTO
 *       (something that reaches clients, directly or field by field) or as internal, so a new
 *       record fails this test until someone decides which it is. Each DTO type is produced by
 *       the real services from a database full of IPs (IPv4 and IPv6, written through sessions,
 *       guests in IP mode and audit rows), serialised with {@link Json#GSON} and scanned; the test
 *       fails if a DTO type was never produced. No DTO may have an IP-like component name, except
 *       {@link IpAccess.SessionIp}, which only {@code ip.view} holders get.</li>
 *   <li><b>Every endpoint</b>: every route of {@link Main#router} is called successfully at least
 *       once while seeding, and every {@code GET} route is then called as an anonymous visitor, a
 *       cookie guest, an IP-mode guest, a user and an admin without {@code ip.view}. All bodies
 *       and headers are scanned. An {@code ip.view} holder is the control: it sees the seeded IPs,
 *       but only in fields named {@code ip}, and only on the endpoints that {@link IpAccess} feeds
 *       ({@code /api/admin/guests}, {@code /api/admin/audit}, {@code /api/admin/ip/...} and a
 *       room's {@code guest-bans}).</li>
 * </ul>
 */
class DtoIpLeakTest {
    private static final String PUBLIC_BASE_URL = "https://tunes.example";

    // Documentation ranges (RFC 5737, RFC 3849): never real clients.
    private static final String V4_SETUP = "203.0.113.7";
    private static final String V4_ALICE = "192.0.2.44";
    private static final String V4_GUEST = "203.0.113.250";
    private static final String V6_ALICE = "2001:db8:1234:5678::42";
    private static final String V6_GUEST = "2001:db8:aaaa:bbbb:1:2:3:4";
    private static final String V6_SERVICE = "2001:db8:ffff::99";
    private static final List<String> SEEDED = List.of("127.0.0.1", V4_SETUP, V4_ALICE, V4_GUEST, V6_ALICE,
            V6_GUEST, V6_SERVICE, "2001:db8:1234:5678:0:0:0:42");

    /** Records that reach clients (directly, or field by field into a JSON body). */
    private static final Set<String> DTOS = Set.of(
            "io.siggi.togethertunes.auth.ActorRef",
            "io.siggi.togethertunes.auth.Accounts$Account",
            "io.siggi.togethertunes.auth.AuditEntries$AuditEntryView",
            "io.siggi.togethertunes.auth.AuditEntries$Page",
            "io.siggi.togethertunes.auth.Guests$Created",
            "io.siggi.togethertunes.auth.Guests$GuestListItem",
            "io.siggi.togethertunes.auth.Guests$GuestView",
            "io.siggi.togethertunes.auth.Guests$Page",
            "io.siggi.togethertunes.auth.Roles$Role",
            "io.siggi.togethertunes.auth.Sessions$Created",
            "io.siggi.togethertunes.auth.Sessions$Page",
            "io.siggi.togethertunes.auth.Sessions$SessionView",
            "io.siggi.togethertunes.auth.Users$Page",
            "io.siggi.togethertunes.auth.Users$RoleRef",
            "io.siggi.togethertunes.auth.Users$UserView",
            "io.siggi.togethertunes.http.EchoEndpoint$Closed",
            "io.siggi.togethertunes.library.Items$ItemPage",
            "io.siggi.togethertunes.library.Items$JobView",
            "io.siggi.togethertunes.library.Items$UndoView",
            "io.siggi.togethertunes.library.Items$You",
            "io.siggi.togethertunes.library.Library$Entry",
            "io.siggi.togethertunes.library.Library$ItemView",
            "io.siggi.togethertunes.library.Library$Page",
            "io.siggi.togethertunes.library.Playlists$Entry",
            "io.siggi.togethertunes.library.Playlists$Page",
            "io.siggi.togethertunes.library.Playlists$Playlist",
            "io.siggi.togethertunes.library.Playlists$PlaylistPage",
            "io.siggi.togethertunes.library.Shares$Page",
            "io.siggi.togethertunes.library.Shares$Share",
            "io.siggi.togethertunes.library.UserLibraries$UserPage",
            "io.siggi.togethertunes.library.UserLibraries$UserSummary",
            "io.siggi.togethertunes.rooms.Rooms$Member",
            "io.siggi.togethertunes.rooms.Rooms$MemberPage",
            "io.siggi.togethertunes.rooms.Rooms$Page",
            "io.siggi.togethertunes.rooms.Rooms$RoomView",
            "io.siggi.togethertunes.rooms.Rooms$You",
            "io.siggi.togethertunes.rooms.RoomGuests$GuestBan",
            "io.siggi.togethertunes.rooms.RoomGuests$GuestBanPage",
            "io.siggi.togethertunes.rooms.RequestSearch$Page",
            "io.siggi.togethertunes.library.ItemSearch$Page",
            "io.siggi.togethertunes.library.ItemSearch$Hit",
            "io.siggi.togethertunes.rooms.RoomHistory$Row",
            "io.siggi.togethertunes.rooms.RoomHistory$Page",
            "io.siggi.togethertunes.settings.StringList",
            "io.siggi.togethertunes.settings.UploadQuota");

    /** The one DTO that carries IPs: built by {@link IpAccess} for {@code ip.view} holders only. */
    private static final String IP_VIEW_DTO = "io.siggi.togethertunes.auth.IpAccess$SessionIp";

    /** Records that never reach a client, and why. */
    private static final Map<String, String> INTERNAL = Map.ofEntries(
            Map.entry("io.siggi.togethertunes.ingest.MediaVersions$Kept", "what the database says about an"
                    + " item's kept undo version, for the undo checks; never sent"),
            Map.entry("io.siggi.togethertunes.ingest.MediaVersions$Undone", "an undo's outcome; the API answers"
                    + " with the item page, the audit row with plain numbers"),
            Map.entry("io.siggi.togethertunes.ingest.IngestItems$VersionChange", "what a publish keeps for undo;"
                    + " server-side only"),
            Map.entry("io.siggi.togethertunes.ingest.MediaVersions$Retired", "an old media version waiting for its"
                    + " clean-up (item id, version, times); never sent"),
            Map.entry("io.siggi.togethertunes.library.MediaApi$Located", "a media file's name and the item's"
                    + " version, to find the file; never sent"),
            Map.entry("io.siggi.togethertunes.DataDir", "server paths"),
            Map.entry("io.siggi.togethertunes.Main$Startup", "startup status"),
            Map.entry("io.siggi.togethertunes.config.Config", "server configuration"),
            Map.entry("io.siggi.togethertunes.config.OutputEncoding", "server configuration (output profile and bitrate)"),
            Map.entry("io.siggi.togethertunes.config.ConfigLoader$Loaded", "server configuration"),
            Map.entry("io.siggi.togethertunes.config.ConfigLoader$Raw", "server configuration"),
            Map.entry("io.siggi.togethertunes.secrets.ServerSecret$Loaded", "the server secret"),
            Map.entry("io.siggi.togethertunes.db.Database$PoolSettings", "database plumbing"),
            Map.entry("io.siggi.togethertunes.db.Database$Tx", "database plumbing"),
            Map.entry("io.siggi.togethertunes.db.Migration", "database plumbing"),
            Map.entry("io.siggi.togethertunes.db.MigrationRunner$Applied", "database plumbing"),
            Map.entry("io.siggi.togethertunes.http.PathPattern$Segment", "routing"),
            Map.entry("io.siggi.togethertunes.http.Route", "routing"),
            Map.entry("io.siggi.togethertunes.auth.Actor$User", "request identity, never serialised"),
            Map.entry("io.siggi.togethertunes.auth.Actor$Guest", "request identity, never serialised"),
            Map.entry("io.siggi.togethertunes.auth.Actor$Anonymous", "request identity, never serialised"),
            Map.entry("io.siggi.togethertunes.auth.ActorResolver$Credentials", "request input (holds the client IP)"),
            Map.entry("io.siggi.togethertunes.auth.ActorResolver$Resolved", "actor resolution"),
            Map.entry("io.siggi.togethertunes.auth.ActorResolver$SessionUser", "actor resolution"),
            Map.entry("io.siggi.togethertunes.auth.ActorResolver$1Row", "actor resolution"),
            Map.entry("io.siggi.togethertunes.auth.ActorResolver$GuestRow", "actor resolution"),
            Map.entry("io.siggi.togethertunes.auth.Accounts$Credential", "password hash"),
            Map.entry("io.siggi.togethertunes.auth.AdminApi$Grants", "permission check"),
            Map.entry("io.siggi.togethertunes.auth.AuthRateLimits", "rate limiters"),
            Map.entry("io.siggi.togethertunes.auth.LoginThrottle$Policy", "rate limiting"),
            Map.entry("io.siggi.togethertunes.auth.LoginThrottle$Refusal", "rate limiting"),
            Map.entry("io.siggi.togethertunes.auth.IdentityTickets$Ticket", "redeemed server-side by the WebSocket"),
            Map.entry("io.siggi.togethertunes.auth.IdentityTickets$Entry", "ticket store"),
            Map.entry("io.siggi.togethertunes.auth.Passwords$Params", "password hashing"),
            Map.entry("io.siggi.togethertunes.auth.Passwords$Parsed", "password hashing"),
            Map.entry("io.siggi.togethertunes.auth.AuthApi$RegisterRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.auth.AuthApi$LoginRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.auth.AuthApi$TokenRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.auth.AuthApi$PasswordChangeRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.auth.MeApi$GuestRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.auth.MeApi$PatchRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.auth.SetupApi$SetupRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.storage.DiskSpace$Usage", "free/total bytes of the data volume, server-side only"),
            Map.entry("io.siggi.togethertunes.storage.DiskSpace$Status", "free space numbers; GET /api/admin/storage builds its JSON field by field (no IPs)"),
            Map.entry("io.siggi.togethertunes.ingest.FfmpegProgress", "tool output, turned into a job's progress number"),
            Map.entry("io.siggi.togethertunes.ingest.ToolResult", "tool output, server-side only"),
            Map.entry("io.siggi.togethertunes.ingest.IngestJob", "ingest_jobs row; GET /api/items/{id}/job (later step) builds its own view of it"),
            Map.entry("io.siggi.togethertunes.ingest.PlayableFiles$Item", "item id, kind, encoding and media version"
                    + " for the rooms' playable-files check; never serialised"),
            Map.entry("io.siggi.togethertunes.ingest.IngestJobs$Recovered", "startup recovery counts, server log only"),
            Map.entry("io.siggi.togethertunes.ingest.IngestJobs$Priority", "claim order rank and kind, inside the ingest queue only"),
            Map.entry("io.siggi.togethertunes.ingest.IngestJobs$Retry", "retry outcome; JobsApi answers with the new job's JobList row"),
            Map.entry("io.siggi.togethertunes.ingest.JobList$Row", "jobs page row; JobsApi.json builds the response from named fields (no IP column is read)"),
            Map.entry("io.siggi.togethertunes.ingest.JobList$ItemRef", "item columns of a jobs page row; JobsApi.json copies named fields, no adder, no IP"),
            Map.entry("io.siggi.togethertunes.ingest.JobList$Page", "a page of JobList rows; JobsApi builds the JSON"),
            Map.entry("io.siggi.togethertunes.ingest.IngestQueue$LiveStep", "a running job's step name and fraction, copied into job views by named fields"),
            Map.entry("io.siggi.togethertunes.ingest.IngestProgress$Status", "room ingest message; toJson writes state, kind, position, step, progress and a short error only"),
            Map.entry("io.siggi.togethertunes.media.Mp4Boxes$Box", "MP4 box position in a media file, server-side only"),
            Map.entry("io.siggi.togethertunes.output.LegacyRtmpKeys$Result", "counts of a startup conversion, printed to the server log"),
            Map.entry("io.siggi.togethertunes.output.VideoSample", "a video sample a program source hands the packager, server-side only"),
            Map.entry("io.siggi.togethertunes.output.ProgramCue", "the room timeline copy handed to the packager, server-side only"),
            Map.entry("io.siggi.togethertunes.output.ProgramPipeline$Opened", "an output's sources and avcC, server-side only"),
            Map.entry("io.siggi.togethertunes.ingest.OutputAssets$Assets", "the output encoding and its card files, server-side only"),
            Map.entry("io.siggi.togethertunes.Main$1LoadedFillers", "the indexed filler cards of one encoding, server-side only"),
            Map.entry("io.siggi.togethertunes.output.ProgramCue$Entry", "a queue entry as the packager sees it, server-side only"),
            Map.entry("io.siggi.togethertunes.output.Unit", "a 2 s program-output unit before muxing, server-side only"),
            Map.entry("io.siggi.togethertunes.output.Unit$Audio", "an audio frame in a program-output unit, server-side only"),
            Map.entry("io.siggi.togethertunes.output.Unit$Video", "a video frame in a program-output unit, server-side only"),
            Map.entry("io.siggi.togethertunes.output.TsMuxer$Pes", "a PES packet inside the MPEG-TS muxer, private"),
            Map.entry("io.siggi.togethertunes.output.TsUnit", "a muxed unit handed to the stream consumers, server-side only"),
            Map.entry("io.siggi.togethertunes.output.OfflineHarness$Result", "offline packager harness run summary, test tooling"),
            Map.entry("io.siggi.togethertunes.output.ScriptedTimeline$Change", "a change of the harness's fake timeline, test tooling"),
            Map.entry("io.siggi.togethertunes.output.ScriptedTimeline$Add", "a scripted action of the harness's fake timeline, private"),
            Map.entry("io.siggi.togethertunes.output.ScriptedTimeline$Clear", "a scripted action of the harness's fake timeline, private"),
            Map.entry("io.siggi.togethertunes.output.ScriptedTimeline$Pause", "a scripted action of the harness's fake timeline, private"),
            Map.entry("io.siggi.togethertunes.output.ScriptedTimeline$Play", "a scripted action of the harness's fake timeline, private"),
            Map.entry("io.siggi.togethertunes.output.ScriptedTimeline$Scheduled", "a scripted action of the harness's fake timeline, private"),
            Map.entry("io.siggi.togethertunes.output.ScriptedTimeline$Seek", "a scripted action of the harness's fake timeline, private"),
            Map.entry("io.siggi.togethertunes.output.ScriptedTimeline$Skip", "a scripted action of the harness's fake timeline, private"),
            Map.entry("io.siggi.togethertunes.output.ScriptedTimeline$Unknown", "a scripted action of the harness's fake timeline, private"),
            Map.entry("io.siggi.togethertunes.output.ProgramOutputs$ItemKind", "an item's kind for the packager, server-side only"),
            Map.entry("io.siggi.togethertunes.output.ProgramOutputs$Snapshot", "a room's timeline and entries copied for the packager, server-side only"),
            Map.entry("io.siggi.togethertunes.output.HlsSegmenter$Segment", "an HLS segment in the ring (bytes served as video/mp2t), server-side only"),
            Map.entry("io.siggi.togethertunes.output.StreamOutputs$Stopped", "why the server turned an output off (reason, time, minutes), part of the internal row; the outputs API copies its fields into a view"),
            Map.entry("io.siggi.togethertunes.output.RtmpPusher$Status", "an RTMP push's supervisor state, server-side until the outputs API turns it into a view"),
            Map.entry("io.siggi.togethertunes.output.LiveTsApi$Target", "what a .ts stream token opens (room id, join point), server-side only"),
            Map.entry("io.siggi.togethertunes.output.LiveTsApi$Connection", "an open .ts connection (token, room, queue) for revocation, server-side only"),
            Map.entry("io.siggi.togethertunes.output.StreamOutputControl$Running", "what an RTMP push was started with (output id, URL, encrypted key), server-side only"),
            Map.entry("io.siggi.togethertunes.output.StreamOutputs$Output", "a stream_outputs row with its token and encrypted key; StreamOutputsApi builds the client view (no IP columns exist)"),
            Map.entry("io.siggi.togethertunes.output.StreamOutputs$Patch", "an outputs API change request, server-side only"),
            Map.entry("io.siggi.togethertunes.output.ProgramPipeline$Running", "a running packager and its parts, server-side only"),
            Map.entry("io.siggi.togethertunes.ingest.CardRenderer$ItemCard", "card renderer input (item metadata + artwork image), server-side only"),
            Map.entry("io.siggi.togethertunes.ingest.CardRenderer$Fitted", "card text layout result, server-side only"),
            Map.entry("io.siggi.togethertunes.ingest.IngestItems$Item", "item columns an ingest job reads, server-side only"),
            Map.entry("io.siggi.togethertunes.ingest.Originals$Original", "what ingest records about a kept original, server-side only"),
            Map.entry("io.siggi.togethertunes.ingest.Loudness$Measurement", "loudnorm pass-1 result, server-side only"),
            Map.entry("io.siggi.togethertunes.ingest.MediaProbe", "ffprobe result of a source file, server-side only"),
            Map.entry("io.siggi.togethertunes.ingest.MediaProbe$Stream", "ffprobe stream of a source file, server-side only"),
            Map.entry("io.siggi.togethertunes.library.UploadMetadata", "title/artist/album derived at upload, written to the item row"),
            Map.entry("io.siggi.togethertunes.library.Items$Edit", "PATCH body, parsed"),
            Map.entry("io.siggi.togethertunes.library.Items$Rights", "the caller's permissions, computed per request"),
            Map.entry("io.siggi.togethertunes.library.Items$Row", "items row; ItemPage is what is sent"),
            Map.entry("io.siggi.togethertunes.library.Items$Purged", "purge result; the API answers 204"),
            Map.entry("io.siggi.togethertunes.retention.RetentionSweeper$Result", "sweep counts, logged only"),
            Map.entry("io.siggi.togethertunes.library.Library$Added", "add result; the API sends its entry"),
            Map.entry("io.siggi.togethertunes.library.Library$ItemRow", "item lookup"),
            Map.entry("io.siggi.togethertunes.library.Playlists$Edit", "PATCH body, parsed"),
            Map.entry("io.siggi.togethertunes.library.Playlists$ItemRow", "item lookup"),
            Map.entry("io.siggi.togethertunes.library.Shares$ItemRow", "item lookup"),
            Map.entry("io.siggi.togethertunes.library.Uploads$Upload", "uploads row; the API builds its own maps"),
            Map.entry("io.siggi.togethertunes.library.Uploads$Completed", "complete's result; the API builds its own map"),
            Map.entry("io.siggi.togethertunes.library.Uploads$ChunkRange", "parsed Content-Range"),
            Map.entry("io.siggi.togethertunes.library.Uploads$Existing", "content-hash lookup"),
            Map.entry("io.siggi.togethertunes.library.Uploads$Started", "start's result; the API builds its own map"),
            Map.entry("io.siggi.togethertunes.library.ContentIdBackfill$Row", "backfill work item, never sent"),
            Map.entry("io.siggi.togethertunes.rooms.RoomSettings$Patched", "patch result; settings go out as toJson"),
            Map.entry("io.siggi.togethertunes.rooms.Rooms$Updated",
                    "update result; the API sends its RoomView (a DTO) plus the adjusted names"),
            Map.entry("io.siggi.togethertunes.library.UploadsApi$CreateRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.library.UploadsApi$CompleteRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.library.YoutubeImports$Imported", "import result; the API builds its own map"),
            Map.entry("io.siggi.togethertunes.library.YoutubeImports$Existing", "youtube_id lookup"),
            Map.entry("io.siggi.togethertunes.library.YoutubeImports$Retried", "retry result; the APIs build their own maps"),
            Map.entry("io.siggi.togethertunes.library.YoutubeImportsApi$ImportRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.library.YoutubeMetadata", "parsed yt-dlp -J output; stored as items columns"),
            Map.entry("io.siggi.togethertunes.rooms.Rooms$Edit", "PATCH body, parsed"),
            Map.entry("io.siggi.togethertunes.rooms.Rooms$Room", "rooms row; RoomView is what is sent"),
            Map.entry("io.siggi.togethertunes.rooms.Rooms$Access", "a room and the caller's role, computed per request"),
            Map.entry("io.siggi.togethertunes.rooms.Rooms$RoleSet", "setRole's result; the API sends its member"),
            Map.entry("io.siggi.togethertunes.rooms.Timeline", "in-memory room timeline; the WebSocket layer builds the"
                    + " messages"),
            Map.entry("io.siggi.togethertunes.rooms.RoomChange", "executor -> listeners/packager, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.LivePlayingItems$Published", "a room's current/next item ids, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$RoomUpdated", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$TimelineChanged", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$EntryStateChanged", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$VotesChanged", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$EntryPinned", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$QueueReordered", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$SkipVotesChanged", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomActor", "who acts on a room queue (ids and rights), built per"
                    + " action, never sent"),
            Map.entry("io.siggi.togethertunes.rooms.ControlSettings", "parsed from settings_json, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomSettings$Def", "a room setting's definition, constant"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$SettingsChanged", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.ControlPolicy$Capabilities", "booleans and a role; the WebSocket"
                    + " state's you object is built from it (no ids)"),
            Map.entry("io.siggi.togethertunes.rooms.QueueControl$VoteSkip", "counts only; the WebSocket layer acks"
                    + " with it"),
            Map.entry("io.siggi.togethertunes.rooms.QueueEntry", "queue_entries row held by the room executor; the"
                    + " WebSocket layer builds the client view (no IP columns exist on it)"),
            Map.entry("io.siggi.togethertunes.rooms.RoomState$ItemInfo", "item status/duration read by the room executor"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$EntryAdded", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$EntryMoved", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$EntryItemsChanged", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.QueueOps$Added", "entry/item ids and state; the WebSocket layer"
                    + " acks with it"),
            Map.entry("io.siggi.togethertunes.rooms.QueueOps$PlaylistAdded", "entry ids and a count; the WebSocket"
                    + " layer acks with it"),
            Map.entry("io.siggi.togethertunes.rooms.RoomQueue$Source", "what a queue.add refers to (item, upload"
                    + " or link), input only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomStore$ItemRow", "item status/duration/source read by the"
                    + " room executor"),
            Map.entry("io.siggi.togethertunes.rooms.RoomGuests$Capabilities", "booleans; turned into a RoomActor and"
                    + " the WebSocket state's you object"),
            Map.entry("io.siggi.togethertunes.rooms.RoomGuests$BanSet", "ban's result; the API sends its ban"),
            Map.entry("io.siggi.togethertunes.rooms.RoomGuests$ServerSwitches", "booleans; RoomsApi copies them"
                    + " into the server-switches answer (swept)"),
            Map.entry("io.siggi.togethertunes.library.ItemSearch$Scope", "user ids whose libraries and playlists"
                    + " a search covers; builds the query"),
            Map.entry("io.siggi.togethertunes.rooms.RoomGuests$GuestRow", "guest name and kind (no IP column read)"),
            Map.entry("io.siggi.togethertunes.rooms.RoomPresence$Counts", "three counts; the WebSocket layer sends"
                    + " them as presence"),
            Map.entry("io.siggi.togethertunes.rooms.RoomPresence$Connection", "connection id -> actor key and mode,"
                    + " in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$PresenceChanged", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$RequestAdded", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.retention.RetentionSweeper$NamedChore", "a housekeeping task and its"
                    + " log name"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$GuestBanned", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$GuestUnbanned", "executor -> listeners, in memory"
                    + " only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomEvent$MemberChanged", "executor -> listeners, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomSnapshot", "copy of the room state for the WebSocket"
                    + " broadcaster (ids, entries, votes, counts); messages are built field by field and scanned"
                    + " in the seeding"),
            Map.entry("io.siggi.togethertunes.rooms.RoomBroadcaster$Join", "broadcaster task, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomBroadcaster$Resend", "broadcaster task, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomBroadcaster$Leave", "broadcaster task, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomBroadcaster$Changed", "broadcaster task, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomBroadcaster$Closed", "broadcaster task, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomBroadcaster$OutputChanged", "broadcaster task, in memory only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomOutputs$Status", "an output's kind, status and push message (key masked), copied field by field into room messages; no IP fields (RoomSocketTest checks the messages for IPs)"),
            Map.entry("io.siggi.togethertunes.rooms.RoomSocket$Joined", "a socket's room and executor, in memory"
                    + " only"),
            Map.entry("io.siggi.togethertunes.rooms.RoomViews$ItemRow", "items columns (no IP column read); the"
                    + " WebSocket item JSON is built from it field by field"),
            Map.entry("io.siggi.togethertunes.rooms.RoomViews$HistoryRow", "queue_entries columns (no IP column"
                    + " read); turned into the WebSocket history"),
            Map.entry("io.siggi.togethertunes.rooms.RoomViews$Lookup", "items and actor refs looked up for one"
                    + " WebSocket message"));

    /** Component names that suggest an IP address. */
    private static final Pattern IP_NAME =
            Pattern.compile("(?i:ips?)|.*[a-z]Ips?|ip[A-Z_].*|(?i:.*addr|.*address(es)?|.*forwarded.*|.*host)");

    private static final Pattern IPV4 =
            Pattern.compile("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?!\\d|\\.\\d)");
    /** Candidates only: at least two colons; validated by {@link #isIpv6}. */
    private static final Pattern IPV6_CANDIDATE =
            Pattern.compile("(?<![0-9A-Za-z_:.])[0-9A-Fa-f]*:[0-9A-Fa-f]*:[0-9A-Fa-f:.]*(?:%[0-9A-Za-z_.]+)?");

    @TempDir
    Path dir;

    private StaticFiles staticFiles;
    private Database database;
    private Settings settings;
    private Router router;
    private WebServer server;
    private Passwords passwords;
    private Accounts accounts;
    private Sessions sessions;
    private Users users;
    private Roles roles;
    private Guests guests;
    private Permissions permissions;
    private Library library;
    private Items items;
    private Playlists playlists;
    private Shares shares;
    private io.siggi.togethertunes.rooms.Rooms rooms;
    private io.siggi.togethertunes.rooms.RoomRegistry roomRegistry;
    private io.siggi.togethertunes.rooms.RoomGuests roomGuests;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(System.currentTimeMillis());

    /** Routes ("METHOD pattern") answered with a 2xx at least once. */
    private final Set<String> succeeded = new TreeSet<>();
    private final List<String> leaks = new ArrayList<>();
    /** Where the ip.view control saw IPs (path, then the IP). */
    private final Set<String> controlHits = new TreeSet<>();

    /** Who is calling. {@code ipView}: holds {@code ip.view} (the control). */
    private record Who(String label, String cookie, String bearer, String forwardedFor, boolean ipView) {
        Who withCookie(String c) {
            return new Who(label, c, bearer, forwardedFor, ipView);
        }

        Who withForwarded(String ip) {
            return new Who(label, cookie, bearer, ip, ipView);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", PUBLIC_BASE_URL), 4, dir)
                .load(null).config();
        staticFiles = StaticFiles.fromClasspath(DtoIpLeakTest.class.getClassLoader(), dir);
        database = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(database);
        BuiltinRoles.ensure(database);
        settings = new Settings(database, config);
        ActorResolver actors = new ActorResolver(database, settings, clock, config.secureCookies());
        passwords = new Passwords(new Passwords.Params(64, 1, 1));
        accounts = new Accounts(database, passwords, clock);
        sessions = new Sessions(database, clock);
        users = new Users(database, passwords, clock);
        roles = new Roles(database);
        permissions = new Permissions(database, settings);
        guests = new Guests(database, settings, config.secureCookies());
        library = new Library(database, TestUploads.youtube(database, config, Tools.from(config), clock), clock);
        items = new Items(database, DataDir.create(dir.resolve("data")), clock);
        io.siggi.togethertunes.ingest.MediaVersions versions = new io.siggi.togethertunes.ingest.MediaVersions(
                DataDir.create(dir.resolve("data")), database, clock::millis,
                io.siggi.togethertunes.ingest.MediaVersions.GRACE);
        versions.undoWindow(() -> 48 * 3_600_000L);
        items.mediaVersions(versions);
        playlists = new Playlists(database, TestUploads.youtube(database, config, Tools.from(config), clock), clock);
        rooms = new io.siggi.togethertunes.rooms.Rooms(database, permissions, clock);
        roomRegistry = new io.siggi.togethertunes.rooms.RoomRegistry(database, clock,
                io.siggi.togethertunes.rooms.PackagerHook.NONE);
        roomGuests = new io.siggi.togethertunes.rooms.RoomGuests(database, settings, permissions, rooms, roomRegistry);
        shares = new Shares(database, TestUploads.youtube(database, config, Tools.from(config), clock), clock);
        AuthRateLimits limits = new AuthRateLimits(new RateLimiter("setup", 1000, 1, clock),
                new RateLimiter("register", 1000, 1, clock), new RateLimiter("login", 1000, 1, clock));
        TestUploads.RoomsWiring roomsWiring = TestUploads.rooms(database, dir, config, settings,
                permissions, actors, new IdentityTickets(), clock);
        router = Main.router(config, staticFiles, actors,
                new SetupApi(accounts, sessions, config.secureCookies(), limits),
                new AuthApi(accounts, sessions, settings, config.secureCookies(), limits),
                new SessionsApi(sessions, accounts, config.secureCookies()),
                new MeApi(accounts, users, permissions, guests, settings, new IdentityTickets()),
                new AdminApi(database, accounts, users, roles, permissions, settings),
                AdminActivityApi.create(database, guests, permissions, clock),
                TestUploads.api(database, dir, config, permissions, clock),
                TestUploads.youtubeApi(database, config, permissions, clock),
                TestUploads.libraryApi(database, config, permissions, clock),
                TestUploads.itemsApi(database, dir, permissions, clock),
                TestUploads.playlistsApi(database, config, permissions, clock),
                TestUploads.sharesApi(database, config, permissions, clock),
                TestUploads.userLibrariesApi(database, config, permissions, clock),
                TestUploads.mediaApi(database, dir, settings, io.siggi.togethertunes.rooms.PlayingItems.NO_ROOMS),
                roomsWiring.api(), roomsWiring.socket());
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
    }

    @AfterEach
    void tearDown() {
        server.close();
        roomRegistry.close();
        staticFiles.close();
        database.close();
    }

    // ------------------------------------------------------------------ the detector

    /** Everything in {@code text} that looks like an IPv4 or IPv6 address. */
    static List<String> ipsIn(String text) {
        List<String> found = new ArrayList<>();
        Matcher v4 = IPV4.matcher(text);
        while (v4.find()) {
            boolean ok = true;
            for (int i = 1; i <= 4; i++) {
                ok &= Integer.parseInt(v4.group(i)) <= 255;
            }
            if (ok) {
                found.add(v4.group());
            }
        }
        Matcher v6 = IPV6_CANDIDATE.matcher(text);
        while (v6.find()) {
            String candidate = v6.group();
            int zone = candidate.indexOf('%');
            String address = zone < 0 ? candidate : candidate.substring(0, zone);
            // "…::1:" or "…::1." at the end of a sentence
            while (!address.isEmpty() && (address.endsWith(".") || (address.endsWith(":") && !address.endsWith("::")))) {
                address = address.substring(0, address.length() - 1);
            }
            if (isIpv6(address)) {
                found.add(zone < 0 ? address : address + candidate.substring(zone));
            }
        }
        return found;
    }

    /** Whether {@code s} is an IPv6 literal. A string with a colon is never looked up in DNS. */
    private static boolean isIpv6(String s) {
        if (s.indexOf(':') < 0 || s.chars().filter(ch -> ch == ':').count() < 2) {
            return false;
        }
        try {
            InetAddress.getByName(s);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    @Test
    void detectorFindsIpAddresses() {
        for (String ip : List.of("203.0.113.7", "0.0.0.0", "255.255.255.255", "10.1.2.3", "::1", "::",
                "2001:db8::42", "2001:0db8:0000:0000:0000:ff00:0042:8329", "fe80::1%eth0", "::ffff:192.0.2.1",
                "2001:db8:1234:5678::", "64:ff9b::192.0.2.33")) {
            assertFalse(ipsIn(ip).isEmpty(), ip);
            assertFalse(ipsIn("seen from " + ip + ".").isEmpty(), ip + " in a sentence");
            assertFalse(ipsIn("{\"x\":\"" + ip + "\"}").isEmpty(), ip + " in JSON");
        }
        assertEquals(List.of("192.0.2.1"), ipsIn("addr=192.0.2.1:8080"));
        assertEquals(List.of("2001:db8::1"), ipsIn("[2001:db8::1]:8080"));
        assertEquals(List.of("2001:db8::1"), ipsIn("last seen at 2001:db8::1: twice"));
    }

    @Test
    void detectorIgnoresOrdinaryValues() {
        for (String s : List.of("0190d4f3-6c2a-7b3e-9f00-1a2b3c4d5e6f", "Mozilla/5.0 (X11; Linux x86_64) Firefox/131.0",
                "Java-http-client/21.0.5", "12:34:56", "1.2.3", "256.1.1.1", "1.2.3.4.5", "ab:cd", "admin.users",
                "1731000000000", "dGhpcyBpcyBhIHRva2VuIG9mIDMyIGJ5dGVzIGxvbmc", "Bearer", "x264 veryfast",
                "a:b:c:d:e:f:g:h", "https://tunes.example/api/me", "sid=abc; Path=/; HttpOnly; SameSite=Lax",
                "Sun, 08 Nov 2026 12:00:00 GMT")) {
            assertEquals(List.of(), ipsIn(s), s);
        }
    }

    // ------------------------------------------------------------------ every DTO type

    /** Every record class in the main output, by binary name. */
    private static Set<String> mainRecords() throws Exception {
        Path root = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(root), "main classes are a directory: " + root);
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String rel = root.relativize(f).toString().replace(f.getFileSystem().getSeparator(), ".");
                String name = rel.substring(0, rel.length() - ".class".length());
                if (name.startsWith("io.siggi.togethertunes.")
                        && Class.forName(name, false, DtoIpLeakTest.class.getClassLoader()).isRecord()) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    @Test
    void everyRecordIsClassifiedAndNoDtoHasAnIpField() throws Exception {
        Set<String> records = mainRecords();
        assertTrue(records.size() > 30, "the scan sees the main records: " + records);
        List<String> unclassified = new ArrayList<>();
        for (String r : records) {
            if (!DTOS.contains(r) && !INTERNAL.containsKey(r) && !r.equals(IP_VIEW_DTO)) {
                unclassified.add(r);
            }
        }
        assertEquals(List.of(), unclassified, "new records: add each to DTOS (it reaches clients; produce it in"
                + " everyDtoTypeSerialisesWithoutIps) or INTERNAL (with the reason)");
        Set<String> stale = new TreeSet<>(DTOS);
        stale.addAll(INTERNAL.keySet());
        stale.add(IP_VIEW_DTO);
        stale.removeAll(records);
        assertEquals(Set.of(), stale, "classified records that no longer exist");

        List<String> ipFields = new ArrayList<>();
        for (String name : DTOS) {
            for (RecordComponent c : Class.forName(name).getRecordComponents()) {
                if (IP_NAME.matcher(c.getName()).matches()) {
                    ipFields.add(name + "." + c.getName());
                }
            }
        }
        assertEquals(List.of(), ipFields, "general DTOs have no IP field; IPs come only from IpAccess");
        boolean sessionIpHasIp = false;
        for (RecordComponent c : Class.forName(IP_VIEW_DTO).getRecordComponents()) {
            sessionIpHasIp |= c.getName().equals("ip");
        }
        assertTrue(sessionIpHasIp, "the name check would catch an ip field");
        assertTrue(IP_NAME.matcher("clientIp").matches() && IP_NAME.matcher("ipLast").matches()
                && IP_NAME.matcher("remoteAddr").matches() && IP_NAME.matcher("ips").matches());
        assertFalse(IP_NAME.matcher("description").matches() || IP_NAME.matcher("ship").matches()
                || IP_NAME.matcher("permissions").matches() || IP_NAME.matcher("isGuest").matches()
                || IP_NAME.matcher("expiresAt").matches() || IP_NAME.matcher("remoteReason").matches()
                || IP_NAME.matcher("initiatedByPeer").matches() || IP_NAME.matcher("tips").matches());
    }

    @Test
    void everyDtoTypeSerialisesWithoutIps() throws Exception {
        seed();

        String aliceId = userId("alice");
        String adminId = userId("siggi");
        List<Object> produced = new ArrayList<>();
        // written with IPs, so the views below have IP-bearing rows behind them
        produced.add(sessions.create(aliceId, Actor.Via.BEARER, "dto-test", V6_SERVICE));
        produced.add(guests.create(GuestIdentity.IP, V6_SERVICE, null));
        produced.add(guests.create(GuestIdentity.COOKIE, V4_GUEST, null));
        accounts.audit(adminId, V6_SERVICE, "test.dto", aliceId, Map.of("note", "dto test"));

        Users.Page userPage = users.list(null, null, Users.LIST_MAX);
        produced.add(userPage);
        for (Users.UserView u : userPage.items()) {
            produced.add(users.get(u.id()));
            produced.add(sessions.list(u.id(), null, Sessions.LIST_MAX));
            produced.add(roles.userRoles(u.id()));
            produced.add(accounts.findEnabled(u.id()));
        }
        Guests.Page guestPage = guests.list(null, null, 200);
        produced.add(guestPage);
        for (Guests.GuestListItem g : guestPage.items()) {
            produced.add(guests.get(g.guest().id()));
        }
        produced.add(new AuditEntries(database).list(null, null, null, 200));
        produced.add(library.list(aliceId, null, null, null, Library.LIST_MAX));
        for (Library.Entry e : library.list(aliceId, null, null, null, Library.LIST_MAX).items()) {
            Actor.User alice = new Actor.User(aliceId, "s", Actor.Via.BEARER);
            produced.add(items.get(alice, e.item().id(), new Items.Rights(true, true)));
            try {
                produced.add(items.job(alice, e.item().id(), new Items.Rights(true, true)));
            } catch (io.siggi.togethertunes.http.HttpError noJob) {
                assertEquals("job_not_found", noJob.code());
            }
        }
        Playlists.Page playlistPage = playlists.list(aliceId, null, Playlists.LIST_MAX);
        produced.add(playlistPage);
        for (Playlists.Playlist p : playlistPage.items()) {
            produced.add(playlists.get(aliceId, p.id()));
        }
        produced.add(shares.list(aliceId, Shares.Direction.IN, null, Shares.LIST_MAX));
        produced.add(shares.list(adminId, Shares.Direction.OUT, null, Shares.LIST_MAX));
        assertFalse(shares.list(aliceId, Shares.Direction.IN, null, Shares.LIST_MAX).items().isEmpty());
        UserLibraries userLibraries = new UserLibraries(database, library, playlists);
        UserLibraries.UserPage browse = userLibraries.users(null, null, UserLibraries.LIST_MAX);
        produced.add(browse);
        for (UserLibraries.UserSummary u : browse.items()) {
            produced.add(userLibraries.library(u.id(), null, null, null, Library.LIST_MAX));
            produced.add(userLibraries.playlists(u.id(), null, Playlists.LIST_MAX));
        }
        Actor.User aliceActor = new Actor.User(aliceId, "s", Actor.Via.BEARER);
        io.siggi.togethertunes.rooms.Rooms.Page roomPage = rooms.list(aliceActor, null,
                io.siggi.togethertunes.rooms.Rooms.LIST_MAX);
        produced.add(roomPage);
        assertFalse(roomPage.items().isEmpty());
        for (io.siggi.togethertunes.rooms.Rooms.RoomView r : roomPage.items()) {
            produced.add(rooms.get(Actor.ANONYMOUS, r.slug()));
            produced.add(rooms.members(aliceActor, r.id(), null, io.siggi.togethertunes.rooms.Rooms.LIST_MAX));
            io.siggi.togethertunes.rooms.RoomGuests.GuestBanPage bans = roomGuests.list(aliceActor, r.id(), null,
                    io.siggi.togethertunes.rooms.RoomGuests.LIST_MAX);
            produced.add(bans);
            produced.add(new io.siggi.togethertunes.rooms.RequestSearch(database, rooms, roomGuests)
                    .search(aliceActor, r.id(), null, null, null, io.siggi.togethertunes.rooms.RequestSearch.LIST_MAX));
            produced.add(new io.siggi.togethertunes.rooms.RequestSearch(database, rooms, roomGuests)
                    .search(aliceActor, r.id(), null, null, null, io.siggi.togethertunes.rooms.RequestSearch.LIST_MAX,
                            true));
            produced.add(new io.siggi.togethertunes.library.ItemSearch(database).search(
                    io.siggi.togethertunes.library.ItemSearch.Scope.server(), aliceActor.userId(), null, null, null,
                    io.siggi.togethertunes.library.ItemSearch.LIST_MAX));
            io.siggi.togethertunes.rooms.RoomHistory roomHistory = new io.siggi.togethertunes.rooms.RoomHistory(
                    database, rooms, settings::roomsRequestHistoryDays, clock);
            produced.add(roomHistory.list(aliceActor, r.id(), null, io.siggi.togethertunes.rooms.RoomHistory.LIST_MAX));
            produced.add(roomHistory.list(Actor.ANONYMOUS, r.id(), null,
                    io.siggi.togethertunes.rooms.RoomHistory.LIST_MAX));
            for (io.siggi.togethertunes.rooms.RoomGuests.GuestBan b : bans.items()) {
                produced.add(roomGuests.get(aliceActor, r.id(), b.guest().id()));
            }
        }
        produced.add(roles.list());
        produced.add(new ArrayList<>(settings.all().values())); // some are null (unset)
        String closes = expect(200, anon(), "GET", "/test/ws-echo/closes?tag=dto-leak", null).body();
        Class<?> closed = Class.forName("io.siggi.togethertunes.http.EchoEndpoint$Closed");
        for (JsonElement e : JsonParser.parseString(closes).getAsJsonObject().getAsJsonArray("closes")) {
            produced.add(Json.GSON.fromJson(e, closed));
        }

        Set<String> seen = new TreeSet<>();
        List<String> found = new ArrayList<>();
        for (Object o : produced) {
            assertNotNull(o);
            collectRecordTypes(o, seen);
            String json = Json.GSON.toJson(o);
            List<String> hits = ipsInJson(JsonParser.parseString(json), "$", new ArrayList<>());
            if (!hits.isEmpty()) {
                found.add(o.getClass().getName() + " " + hits + ": " + json);
            }
            for (String ip : SEEDED) {
                if (json.contains(ip)) {
                    found.add(o.getClass().getName() + " contains " + ip + ": " + json);
                }
            }
        }
        assertEquals(List.of(), found, "DTOs must not carry IP addresses");
        Set<String> missing = new TreeSet<>(DTOS);
        missing.removeAll(seen);
        assertEquals(Set.of(), missing, "every DTO type is produced and scanned");
        assertTrue(guestPage.items().size() >= 3 && userPage.items().size() >= 3, "the fixture has rows");

        // The control: the IP-only DTO does carry the IPs the views above left out.
        Actor admin = new Actor.User(adminId, "control", Actor.Via.COOKIE);
        List<IpAccess.SessionIp> withIps = new IpAccess(database, permissions, clock).sessions(admin, aliceId);
        Set<String> ips = new TreeSet<>();
        withIps.forEach(s -> ips.add(s.ip()));
        assertTrue(ips.containsAll(Set.of(V6_SERVICE, V4_ALICE)), ips.toString());
        for (IpAccess.SessionIp s : withIps) {
            assertFalse(ipsIn(Json.GSON.toJson(s)).isEmpty(), "the detector sees SessionIp's ip");
        }
    }

    /** Marks the record classes in {@code o}'s object graph (records, collections, maps). */
    private static void collectRecordTypes(Object o, Set<String> seen) throws Exception {
        if (o == null) {
            return;
        }
        if (o instanceof Collection<?> c) {
            for (Object e : c) {
                collectRecordTypes(e, seen);
            }
        } else if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                collectRecordTypes(e.getKey(), seen);
                collectRecordTypes(e.getValue(), seen);
            }
        } else if (o.getClass().isRecord()) {
            seen.add(o.getClass().getName());
            for (RecordComponent c : o.getClass().getRecordComponents()) {
                c.getAccessor().setAccessible(true);
                collectRecordTypes(c.getAccessor().invoke(o), seen);
            }
        }
    }

    /** Every string (value or key) in {@code e} that holds an IP, as {@code "path: value"}. */
    private static List<String> ipsInJson(JsonElement e, String path, List<String> out) {
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
                if (!ipsIn(m.getKey()).isEmpty()) {
                    out.add(path + " key " + m.getKey());
                }
                ipsInJson(m.getValue(), path + "." + m.getKey(), out);
            }
        } else if (e.isJsonArray()) {
            int i = 0;
            for (JsonElement x : e.getAsJsonArray()) {
                ipsInJson(x, path + "[" + i++ + "]", out);
            }
        } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            if (!ipsIn(e.getAsString()).isEmpty()) {
                out.add(path + ": " + e.getAsString());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ every endpoint

    @Test
    void noEndpointShowsIpsWithoutIpView() throws Exception {
        Fixture f = seed();

        // An IP-mode guest (IPv6), swept while IP mode is on.
        expect(200, f.admin, "PATCH", "/api/admin/settings", "{\"guests.identifyBy\":\"ip\"}");
        Who ipGuest = new Who("ip guest", null, null, V6_GUEST, false);
        expect(201, ipGuest, "POST", "/api/me/guest", null);
        JsonObject me = json(expect(200, ipGuest, "GET", "/api/me", null));
        assertEquals("guest", me.get("kind").getAsString(), me.toString());
        sweep(ipGuest, f);
        expect(200, f.admin, "PATCH", "/api/admin/settings", "{\"guests.identifyBy\":\"cookie\"}");

        for (Who who : List.of(anon(), f.cookieGuest, f.alice, f.aliceBearer, f.help, f.admin)) {
            sweep(who, f);
        }

        assertEquals(List.of(), leaks, "IP addresses in responses");
        Set<String> missing = new TreeSet<>(router.routes());
        missing.removeAll(succeeded);
        assertEquals(Set.of(), missing, "every route answered 2xx at least once");

        // The control: ip.view sees the seeded IPs where IpAccess puts them.
        for (String expected : List.of(
                "/api/admin/guests " + V4_GUEST, "/api/admin/guests " + V6_GUEST,
                "/api/admin/audit " + V4_SETUP, "/api/admin/audit " + V4_ALICE,
                "/api/admin/ip/sessions/" + f.aliceId + " " + V4_ALICE,
                "/api/rooms/" + f.roomSlug + "/guest-bans " + V4_GUEST,
                "/api/rooms/" + f.roomSlug + "/guest-bans/" + f.cookieGuestId + " " + V4_GUEST)) {
            assertTrue(controlHits.contains(expected), expected + " in " + controlHits);
        }
        assertTrue(controlHits.stream().anyMatch(h -> h.startsWith("/api/admin/ip/sessions/" + f.aliceId + " 2001:db8:1234:5678:")),
                controlHits.toString());
    }

    /** GETs every GET route as {@code who} (whatever the status) and scans the answers. */
    private void sweep(Who who, Fixture f) throws Exception {
        List<String> paths = List.of("/", "/test/ws-echo.html", "/js/test/ws-echo.js", "/ws/echo", "/ws",
                "/test/ws-echo/closes?tag=dto-leak", "/api/setup", "/api/me", "/api/me/sessions",
                "/api/admin/users", "/api/admin/users?q=ali", "/api/admin/users/" + f.aliceId,
                "/api/admin/users/" + f.aliceId + "/roles", "/api/admin/roles", "/api/admin/roles/" + f.roleId,
                "/api/admin/roles/" + f.roleId + "/permissions", "/api/admin/settings", "/api/admin/storage", "/api/admin/encoding", "/api/admin/guests",
                "/api/admin/audit", "/api/admin/audit?action=auth.login_failed", "/api/admin/ip/sessions/" + f.aliceId,
                "/api/admin/ip/sessions/" + f.adminId, "/api/uploads/" + f.uploadId, "/api/library",
                "/api/library?q=video&kind=video", "/api/items/search", "/api/items/search?q=video", "/api/items/" + f.itemId, "/api/items/" + f.itemId + "/job",
                "/api/playlists", "/api/playlists/" + f.playlistId, "/api/shares", "/api/shares?direction=out",
                "/api/users", "/api/users?q=ali", "/api/users/" + f.aliceId + "/library",
                "/api/users/" + f.aliceId + "/library?q=video", "/api/users/" + f.aliceId + "/playlists",
                "/api/users/" + f.aliceId + "/playlists/" + f.playlistId, "/media/" + f.itemId + "/audio.m4a",
                "/media/" + f.itemId + "/v/0/audio.m4a", "/items/" + f.itemId, "/rooms/" + f.roomSlug, "/rooms/" + f.roomSlug + "/manage", "/r/" + f.roomSlug, "/api/rooms", "/api/rooms/" + f.roomSlug,
                "/api/rooms/" + f.roomSlug + "/members", "/api/rooms/" + f.roomSlug + "/guest-bans",
                "/api/rooms/" + f.roomSlug + "/guest-bans/" + f.cookieGuestId,
                "/api/rooms/" + f.roomSlug + "/search?q=video", "/api/rooms/" + f.roomSlug + "/search?all=1", "/api/rooms/" + f.roomSlug + "/history",
                "/api/rooms/" + f.roomSlug + "/server-switches");
        Set<String> getRoutes = new TreeSet<>();
        router.routes().stream().filter(r -> r.startsWith("GET ")).forEach(getRoutes::add);
        Set<String> swept = new TreeSet<>();
        for (String p : paths) {
            swept.add(routeOf("GET", p));
            send(who, "GET", p, null);
        }
        assertEquals(getRoutes, swept, "the sweep covers every GET route");
    }

    private record Fixture(Who admin, Who alice, Who aliceBearer, Who help, Who cookieGuest, String adminId,
                           String aliceId, String roleId, String uploadId, String itemId, String playlistId,
                           String roomSlug, String cookieGuestId) {
    }

    /**
     * Seeds IP-bearing data through every route (each answering 2xx at least once): setup,
     * registration, logins (one failing), a bearer token, a password change, sessions listed and
     * revoked, roles and users administered, a cookie guest renamed, the echo socket closed.
     */
    private Fixture seed() throws Exception {
        Who anon = anon();
        expect(200, anon, "GET", "/api/setup", null);
        HttpResponse<String> setup = expect(201, anon.withForwarded(V4_SETUP), "POST", "/api/setup",
                "{\"username\":\"siggi\",\"password\":\"correct horse\"}");
        Who admin = new Who("admin (ip.view)", cookie(setup, "sid"), null, V4_SETUP, true);
        String adminId = json(setup).getAsJsonObject("user").get("id").getAsString();
        expect(200, admin, "PATCH", "/api/admin/settings",
                "{\"registration.mode\":\"open\",\"guests.allowCustomNames\":true}");

        HttpResponse<String> registered = expect(201, anon.withForwarded(V4_ALICE), "POST", "/api/auth/register",
                "{\"username\":\"alice\",\"password\":\"alice-password-1\",\"displayName\":\"Alice\"}");
        String aliceId = json(registered).getAsJsonObject("user").get("id").getAsString();
        Who aliceFirst = new Who("alice", cookie(registered, "sid"), null, V4_ALICE, false);
        expect(401, anon.withForwarded(V4_ALICE), "POST", "/api/auth/login",
                "{\"username\":\"alice\",\"password\":\"wrong password\"}");
        Who alice = new Who("alice", cookie(expect(200, anon.withForwarded(V6_ALICE), "POST", "/api/auth/login",
                "{\"username\":\"alice\",\"password\":\"alice-password-1\"}"), "sid"), null, V6_ALICE, false);
        String token = json(expect(201, anon.withForwarded(V6_ALICE), "POST", "/api/auth/token",
                "{\"username\":\"alice\",\"password\":\"alice-password-1\"}")).get("token").getAsString();
        Who aliceBearer = new Who("alice (bearer)", null, token, V6_ALICE, false);
        expect(200, aliceBearer, "GET", "/api/me", null);
        expect(200, alice, "PATCH", "/api/me", "{\"displayName\":\"Alice A\"}");

        // sessions: list, revoke one, revoke the others, change the password, log out a spare one
        String firstSession = null;
        for (JsonElement e : json(expect(200, alice, "GET", "/api/me/sessions", null)).getAsJsonArray("items")) {
            JsonObject s = e.getAsJsonObject();
            if (!s.get("current").getAsBoolean() && s.get("kind").getAsString().equals("cookie")) {
                firstSession = s.get("id").getAsString();
            }
        }
        assertNotNull(firstSession);
        expectOk(alice, "DELETE", "/api/me/sessions/" + firstSession, null);
        expectOk(alice, "DELETE", "/api/me/sessions", null);
        expectOk(alice, "POST", "/api/me/password",
                "{\"currentPassword\":\"alice-password-1\",\"newPassword\":\"alice-password-2\"}");
        Who spare = new Who("alice", cookie(expect(200, anon.withForwarded(V4_ALICE), "POST", "/api/auth/login",
                "{\"username\":\"alice\",\"password\":\"alice-password-2\"}"), "sid"), null, V4_ALICE, false);
        expectOk(spare, "POST", "/api/auth/logout", null);
        Who aliceAgain = new Who("alice", cookie(expect(200, anon.withForwarded(V4_ALICE), "POST", "/api/auth/login",
                "{\"username\":\"alice\",\"password\":\"alice-password-2\"}"), "sid"), null, V4_ALICE, false);
        aliceBearer = new Who("alice (bearer)", null, json(expect(201, anon.withForwarded(V6_ALICE), "POST",
                "/api/auth/token", "{\"username\":\"alice\",\"password\":\"alice-password-2\"}"))
                .get("token").getAsString(), V6_ALICE, false);
        expect(200, aliceFirst.withCookie(aliceAgain.cookie()), "GET", "/api/me", null);

        // roles and users, administered by the admin
        String roleId = json(expect(201, admin, "POST", "/api/admin/roles",
                "{\"name\":\"helpdesk\",\"permissions\":[\"admin.users\"]}")).get("id").getAsString();
        expect(200, admin, "GET", "/api/admin/roles", null);
        expect(200, admin, "GET", "/api/admin/roles/" + roleId, null);
        expect(200, admin, "PATCH", "/api/admin/roles/" + roleId, "{\"name\":\"help desk\"}");
        expect(200, admin, "GET", "/api/admin/roles/" + roleId + "/permissions", null);
        expect(200, admin, "PUT", "/api/admin/roles/" + roleId + "/permissions",
                "{\"permissions\":[\"admin.users\",\"admin.roles\",\"admin.settings\"]}");
        String helpId = json(expect(201, admin, "POST", "/api/admin/users",
                "{\"username\":\"help\",\"password\":\"temporary-1\"}")).get("id").getAsString();
        expect(200, admin, "GET", "/api/admin/users", null);
        expect(200, admin, "GET", "/api/admin/users/" + helpId, null);
        expect(200, admin, "PATCH", "/api/admin/users/" + helpId, "{\"displayName\":\"Help Desk\"}");
        expect(200, admin, "GET", "/api/admin/users/" + helpId + "/roles", null);
        expect(200, admin, "PUT", "/api/admin/users/" + helpId + "/roles", "{\"roleIds\":[\"" + roleId + "\"]}");
        expectOk(admin, "POST", "/api/admin/users/" + helpId + "/password", "{\"password\":\"temporary-2\"}");
        Who help = new Who("help desk (no ip.view)", cookie(expect(200, anon.withForwarded(V4_SETUP), "POST",
                "/api/auth/login", "{\"username\":\"help\",\"password\":\"temporary-2\"}"), "sid"), null, V4_SETUP, false);
        String spareRole = json(expect(201, help, "POST", "/api/admin/roles", "{\"name\":\"spare\"}"))
                .get("id").getAsString();
        expectOk(help, "DELETE", "/api/admin/roles/" + spareRole, null);
        expect(200, help, "GET", "/api/admin/settings", null);
        expect(200, help, "PATCH", "/api/admin/settings", "{\"guests.nameBlocklist\":[\"badword\"]}");
        expect(200, help, "GET", "/api/admin/guests", null);
        expect(200, help, "GET", "/api/admin/audit", null);
        expect(200, admin, "GET", "/api/admin/ip/sessions/" + aliceId, null);

        // a cookie guest, renamed both ways
        HttpResponse<String> guestCreated = expect(201, anon.withForwarded(V4_GUEST), "POST", "/api/me/guest", null);
        Who cookieGuest = new Who("cookie guest", cookie(guestCreated, "gid"), null, V4_GUEST, false);
        expect(200, cookieGuest, "PATCH", "/api/me/guest", "{\"name\":\"Blue Fox\"}");
        expect(200, cookieGuest, "PATCH", "/api/me", "{\"name\":\"Blue Fox Two\"}");
        expect(200, cookieGuest, "GET", "/api/me", null);

        // the test socket: a tagged close, its report, a plain GET, the test page
        WebsocketHeaders headers = new WebsocketHeaders();
        headers.setHeader("Origin", PUBLIC_BASE_URL);
        headers.setHeader("X-Forwarded-For", V6_ALICE);
        try (SimpleWebsocket ws = new WebSocketConnector()
                .connect(URI.create("ws://127.0.0.1:" + server.port() + "/ws/echo?tag=dto-leak"), headers)) {
            ws.send(WebSocketMessage.create("hello"));
            assertEquals("hello", ws.read().getText());
        }
        succeeded.add("GET /ws/echo"); // the 101 above
        for (int i = 0; i < 100 && json(expect(200, anon, "GET", "/test/ws-echo/closes?tag=dto-leak", null))
                .getAsJsonArray("closes").isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertFalse(json(expect(200, anon, "GET", "/test/ws-echo/closes?tag=dto-leak", null))
                .getAsJsonArray("closes").isEmpty(), "the close was recorded");
        expectOk(anon, "POST", "/test/ws-echo/report", "{\"ok\":true,\"from\":\"dto test\"}");
        expectOk(anon, "POST", "/api/diagnostics", "{\"ref\":\"dto\",\"decisions\":[]}");
        expect(200, anon, "GET", "/test/ws-echo.html", null);

        // an upload by alice: started, one chunk, its status, completed as a duplicate of an
        // existing item (so no ffprobe is needed)
        insertUploadItem(adminId, "hello");
        String uploadId = json(expect(201, alice, "POST", "/api/uploads", "{\"filename\":\"hello.mp3\",\"size\":5}"))
                .get("uploadId").getAsString();
        expect(200, alice, "PUT", "/api/uploads/" + uploadId, "hello", Map.of("Content-Range", "bytes 0-4/5"));
        expect(200, alice, "GET", "/api/uploads/" + uploadId, null);
        assertTrue(json(expect(200, alice, "POST", "/api/uploads/" + uploadId + "/complete", null))
                .get("deduplicated").getAsBoolean());

        // a YouTube import by alice of a video that is already ready (cached, so no yt-dlp)
        insertYoutubeItem(adminId, "dQw4w9WgXcQ");
        assertTrue(json(expect(200, alice, "POST", "/api/imports/youtube",
                "{\"url\":\"https://youtu.be/dQw4w9WgXcQ?t=3\"}")).get("cached").getAsBoolean());

        // alice's library: listed, an item added by id and removed again, one shared by the admin
        String extra = insertItem(adminId);
        expect(201, alice, "PUT", "/api/library/" + extra, null);
        expectOk(alice, "DELETE", "/api/library/" + extra, null);
        // the admin shares an item with alice (with a message, the username in another case); alice
        // lists her shares and marks them seen, the admin lists his outgoing ones
        String shared = insertItem(adminId);
        expect(201, admin, "POST", "/api/shares", "{\"itemId\":\"" + shared
                + "\",\"toUsername\":\"ALICE\",\"message\":\"Listen to this\"}");
        expect(200, alice, "GET", "/api/shares", null);
        expect(200, alice, "POST", "/api/shares/seen", "{\"all\":true}");
        expect(200, admin, "GET", "/api/shares?direction=out", null);
        assertTrue(json(expect(200, alice, "GET", "/api/library", null)).getAsJsonArray("items").size() >= 3);

        // items by id: alice's upload (deduplicated onto the admin's item, so it has no job of its
        // own: give it one), read, its job, edited by the admin; another item purged
        String itemId = json(expect(200, alice, "GET", "/api/uploads/" + uploadId, null)).get("itemId").getAsString();
        database.write(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO ingest_jobs (id, item_id, kind,"
                    + " state, attempts, progress, created_at, started_at, finished_at, log)"
                    + " VALUES (?, ?, 'upload', 'done', 1, 1, 1, 1, 2, 'done')")) {
                ps.setString(1, io.siggi.togethertunes.db.UuidV7.newId());
                ps.setString(2, itemId);
                ps.executeUpdate();
            }
            return null;
        });
        expect(200, alice, "GET", "/api/items/" + itemId, null);
        expect(200, alice, "GET", "/api/items/" + itemId + "/job", null);
        Path itemDir = dir.resolve("data").resolve("media").resolve(itemId);
        Files.createDirectories(itemDir);
        Files.write(itemDir.resolve("audio.m4a"), new byte[16]);
        expect(200, alice, "GET", "/media/" + itemId + "/audio.m4a", null);
        expect(200, admin, "PATCH", "/api/items/" + itemId, "{\"album\":\"Album\"}");
        expect(204, admin, "DELETE", "/api/items/" + insertItem(adminId), null);
        // undo of a media change: alice's own item, edited (version 2, version 1 kept for undo), undone
        String undoItem = insertItem(aliceId);
        expect(201, alice, "PUT", "/api/library/" + undoItem, null);
        for (int v = 1; v <= 2; v++) {
            Path f = dir.resolve("data").resolve("media").resolve(undoItem).resolve("v" + v).resolve("mezz.mp4");
            Files.createDirectories(f.getParent());
            Files.write(f, new byte[16]);
            new io.siggi.togethertunes.ingest.IngestItems(database).published(undoItem, true, 1000,
                    io.siggi.togethertunes.config.OutputEncoding.DEFAULT, io.siggi.togethertunes.config.X264Preset.VERYFAST,
                    true, null, null, v, new io.siggi.togethertunes.ingest.IngestItems.VersionChange(v == 1 ? null : 1,
                            true, null, clock.millis()));
        }
        expect(200, alice, "POST", "/api/items/" + undoItem + "/undo", null);

        // alice's playlists: one created, renamed, filled (the same item twice), an entry moved and
        // one removed; another created and deleted
        String playlistId = json(expect(201, alice, "POST", "/api/playlists",
                "{\"name\":\"Mix\",\"description\":\"Songs\"}")).get("id").getAsString();
        expect(200, alice, "PATCH", "/api/playlists/" + playlistId, "{\"name\":\"Road trip\"}");
        String first = json(expect(201, alice, "POST", "/api/playlists/" + playlistId + "/entries",
                "{\"itemId\":\"" + itemId + "\"}")).get("id").getAsString();
        String second = json(expect(201, alice, "POST", "/api/playlists/" + playlistId + "/entries",
                "{\"itemId\":\"" + itemId + "\",\"beforeEntryId\":\"" + first + "\"}")).get("id").getAsString();
        expect(201, alice, "POST", "/api/playlists/" + playlistId + "/entries", "{\"itemId\":\"" + shared + "\"}");
        expect(200, alice, "PATCH", "/api/playlists/" + playlistId + "/entries/" + second, "{\"beforeEntryId\":null}");
        expect(204, alice, "DELETE", "/api/playlists/" + playlistId + "/entries/" + first, null);
        String doomed = json(expect(201, alice, "POST", "/api/playlists", "{\"name\":\"Doomed\"}"))
                .get("id").getAsString();
        expect(204, alice, "DELETE", "/api/playlists/" + doomed, null);
        expect(200, alice, "GET", "/api/playlists", null);
        expect(200, alice, "GET", "/api/playlists/" + playlistId, null);

        // the admin (library.viewAll) browses users, alice's library and her playlists
        expect(200, admin, "GET", "/api/users?q=ali", null);
        expect(200, admin, "GET", "/api/users/" + aliceId + "/library", null);
        expect(200, admin, "GET", "/api/users/" + aliceId + "/playlists", null);
        expect(200, admin, "GET", "/api/users/" + aliceId + "/playlists/" + playlistId, null);

        // rooms: alice creates one (public), renames it, gives the help desk account a role and
        // takes it away again, lets the admin in, lists its members; another is created and deleted
        String roomSlug = json(expect(201, alice, "POST", "/api/rooms",
                "{\"name\":\"Alice's Room\",\"visibility\":\"public\"}")).get("slug").getAsString();
        expect(200, alice, "PATCH", "/api/rooms/" + roomSlug, "{\"name\":\"Alice's Lounge\"}");
        expect(201, alice, "PUT", "/api/rooms/" + roomSlug + "/members/" + helpId, "{\"role\":\"moderator\"}");
        expect(204, alice, "DELETE", "/api/rooms/" + roomSlug + "/members/" + helpId, null);
        expect(201, alice, "POST", "/api/rooms/" + roomSlug + "/members", "{\"username\":\"help\",\"role\":\"member\"}");
        expect(204, alice, "DELETE", "/api/rooms/" + roomSlug + "/members/" + helpId, null);
        expect(200, alice, "GET", "/api/rooms/" + roomSlug + "/server-switches", null);
        expect(201, alice, "PUT", "/api/rooms/" + roomSlug + "/members/" + adminId, "{\"role\":\"member\"}");
        expect(200, alice, "GET", "/api/rooms/" + roomSlug + "/members", null);
        expect(200, cookieGuest, "GET", "/api/rooms/" + roomSlug, null);
        expect(200, cookieGuest, "GET", "/api/rooms/" + roomSlug + "/search", null);
        expect(200, anon, "GET", "/api/rooms", null);
        // guest bans: alice looks at the cookie guest, bans it, lists, lifts and bans it again (it stays banned)
        String cookieGuestId = json(expect(200, cookieGuest, "GET", "/api/me", null)).get("id").getAsString();
        String banPath = "/api/rooms/" + roomSlug + "/guest-bans/" + cookieGuestId;
        expect(200, alice, "GET", banPath, null);
        expect(201, alice, "PUT", banPath, null);
        expect(200, alice, "GET", "/api/rooms/" + roomSlug + "/guest-bans", null);
        expect(204, alice, "DELETE", banPath, null);
        expect(201, alice, "PUT", banPath, null);
        // the history: an entry the cookie guest added that has played, listed for alice
        String roomId = json(expect(200, alice, "GET", "/api/rooms/" + roomSlug, null)).get("id").getAsString();
        String requestedItem = insertItem(adminId);
        database.write(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO queue_entries (id, room_id, item_id,"
                    + " added_by_guest_id, added_at, state, position, played_at, via, added_by_name)"
                    + " VALUES (?, ?, ?, ?, 1, 'played', 1, ?, 'upload', 'Blue Fox Two')")) {
                ps.setString(1, io.siggi.togethertunes.db.UuidV7.newId());
                ps.setString(2, roomId);
                ps.setString(3, requestedItem);
                ps.setString(4, cookieGuestId);
                ps.setLong(5, clock.millis());
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals(1, json(expect(200, alice, "GET", "/api/rooms/" + roomSlug + "/history", null))
                .getAsJsonArray("items").size());
        // the room socket: alice joins and plays the requested item, an anonymous visitor follows;
        // every message is scanned like a response body
        try (SimpleWebsocket owner = roomSocket(alice); SimpleWebsocket visitor = roomSocket(anon.withForwarded(V4_GUEST))) {
            owner.send(WebSocketMessage.create("{\"type\":\"join\",\"rid\":1,\"room\":\"" + roomSlug + "\"}"));
            readSocketUntil(owner, "alice socket", "\"rid\":1");
            visitor.send(WebSocketMessage.create("{\"type\":\"join\",\"rid\":1,\"room\":\"" + roomSlug
                    + "\",\"mode\":\"remote\"}"));
            readSocketUntil(visitor, "anonymous socket", "\"type\":\"state\"");
            owner.send(WebSocketMessage.create("{\"type\":\"queue.add\",\"rid\":2,\"itemId\":\"" + requestedItem
                    + "\"}"));
            // the entry (queued or playing) with its item, in both sockets' state
            readSocketUntil(owner, "alice socket", "\"id\":\"" + requestedItem + "\"");
            readSocketUntil(visitor, "anonymous socket", "\"id\":\"" + requestedItem + "\"");
        }
        succeeded.add("GET /ws"); // the 101s above
        String doomedRoom = json(expect(201, alice, "POST", "/api/rooms", "{\"name\":\"Doomed\"}"))
                .get("id").getAsString();
        expect(204, alice, "DELETE", "/api/rooms/" + doomedRoom, null);

        return new Fixture(admin, alice, aliceBearer, help, cookieGuest, adminId, aliceId, roleId, uploadId, itemId,
                playlistId, roomSlug, cookieGuestId);
    }

    /** A room socket ({@code /ws}) opened as {@code who}. */
    private SimpleWebsocket roomSocket(Who who) throws IOException {
        WebsocketHeaders headers = new WebsocketHeaders();
        headers.setHeader("Origin", PUBLIC_BASE_URL);
        if (who.cookie() != null) {
            headers.setHeader("Cookie", who.cookie());
        }
        if (who.bearer() != null) {
            headers.setHeader("Authorization", "Bearer " + who.bearer());
        }
        if (who.forwardedFor() != null) {
            headers.setHeader("X-Forwarded-For", who.forwardedFor());
        }
        return new WebSocketConnector().setMaxPayloadLength(1 << 22)
                .connect(URI.create("ws://127.0.0.1:" + server.port() + "/ws"), headers);
    }

    /** Reads (and scans) socket messages until one contains {@code marker}. */
    private void readSocketUntil(SimpleWebsocket ws, String where, String marker) throws IOException {
        for (int i = 0; i < 50; i++) {
            WebSocketMessage m = ws.read();
            assertNotNull(m, where + " closed");
            if (m.isControlMessage()) {
                continue;
            }
            String text = m.getText();
            if (!ipsIn(text).isEmpty()) {
                leaks.add(where + " message: " + ipsIn(text));
            }
            assertFalse(text.contains("\"type\":\"error\""), text);
            if (text.contains(marker)) {
                return;
            }
        }
        throw new AssertionError(where + ": no message with " + marker);
    }

    /** A ready video item added by {@code userId}, in nobody's library. */
    private String insertItem(String userId) throws Exception {
        String id = io.siggi.togethertunes.db.UuidV7.newId();
        database.write(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist,"
                    + " source, added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, 'video', 'Some Video', 'Someone', 'upload', ?, 1, 'ready', '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, userId);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    /** A ready YouTube item for {@code videoId}. */
    private void insertYoutubeItem(String userId, String videoId) throws Exception {
        database.write(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " youtube_id, added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, 'video', 'Video', 'youtube', ?, ?, 1, 'ready', '720p30', 1)")) {
                ps.setString(1, io.siggi.togethertunes.db.UuidV7.newId());
                ps.setString(2, videoId);
                ps.setString(3, userId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** A ready upload item whose content hash is that of {@code content}. */
    private void insertUploadItem(String userId, String content) throws Exception {
        String sha = io.siggi.togethertunes.library.Uploads.sha256Hex(
                new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        database.write(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " content_sha256, added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, 'audio', 'Hello', 'upload', ?, ?, 1, 'ready', '720p30', 1)")) {
                ps.setString(1, io.siggi.togethertunes.db.UuidV7.newId());
                ps.setString(2, sha);
                ps.setString(3, userId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Who anon() {
        return new Who("anonymous", null, null, null, false);
    }

    private String userId(String username) throws Exception {
        for (Users.UserView u : users.list(null, null, Users.LIST_MAX).items()) {
            if (u.username().equals(username)) {
                return u.id();
            }
        }
        throw new AssertionError("no user " + username);
    }

    private HttpResponse<String> expect(int status, Who who, String method, String path, String body) throws Exception {
        return expect(status, who, method, path, body, Map.of());
    }

    private HttpResponse<String> expect(int status, Who who, String method, String path, String body,
                                        Map<String, String> headers) throws Exception {
        HttpResponse<String> r = send(who, method, path, body, headers);
        assertEquals(status, r.statusCode(), who.label() + " " + method + " " + path + ": " + r.body());
        return r;
    }

    private HttpResponse<String> expectOk(Who who, String method, String path, String body) throws Exception {
        HttpResponse<String> r = send(who, method, path, body);
        assertTrue(r.statusCode() / 100 == 2, who.label() + " " + method + " " + path + ": " + r.statusCode() + " " + r.body());
        return r;
    }

    /** Sends one request, notes its route if it succeeded, and scans the answer. */
    private HttpResponse<String> send(Who who, String method, String path, String body) throws Exception {
        return send(who, method, path, body, Map.of());
    }

    private HttpResponse<String> send(Who who, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Origin", PUBLIC_BASE_URL);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (who.cookie() != null) {
            b.header("Cookie", who.cookie());
        }
        if (who.bearer() != null) {
            b.header("Authorization", "Bearer " + who.bearer());
        }
        if (who.forwardedFor() != null) {
            b.header("X-Forwarded-For", who.forwardedFor()); // the loopback peer is a trusted proxy
        }
        headers.forEach(b::header);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        // The short link /r/{slug} only ever redirects (its Location is scanned like any header).
        if (r.statusCode() / 100 == 2 || (r.statusCode() == 302 && path.startsWith("/r/"))) {
            succeeded.add(routeOf(method, path));
        }
        scan(who, method + " " + path, path, r);
        return r;
    }

    private void scan(Who who, String what, String path, HttpResponse<String> r) {
        String where = who.label() + " " + what + " (" + r.statusCode() + ")";
        r.headers().map().forEach((name, values) -> {
            for (String v : values) {
                if (!ipsIn(v).isEmpty()) {
                    leaks.add(where + " header " + name + ": " + v);
                }
            }
        });
        String bodyText = r.body();
        if (bodyText.isEmpty()) {
            return;
        }
        String type = r.headers().firstValue("Content-Type").orElse("");
        if (!type.startsWith("application/json")) {
            if (!ipsIn(bodyText).isEmpty()) {
                leaks.add(where + " body: " + ipsIn(bodyText));
            }
            return;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(bodyText);
        } catch (JsonSyntaxException e) {
            leaks.add(where + " body is not JSON: " + bodyText);
            return;
        }
        boolean ipEndpoint = path.startsWith("/api/admin/guests") || path.startsWith("/api/admin/audit")
                || path.startsWith("/api/admin/ip/") || path.matches("/api/rooms/[^/]+/guest-bans(/[^/?]+)?(\\?.*)?");
        String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        walk(root, "$", (keyPath, key, value) -> {
            if (key != null && key.equals("ip")) {
                if (!who.ipView() || !ipEndpoint) {
                    leaks.add(where + " has an ip field at " + keyPath);
                }
                if (value != null) {
                    if (ipsIn(value).isEmpty() && who.ipView()) {
                        leaks.add(where + " ip field holds a non-IP at " + keyPath + ": " + value);
                    }
                    controlHits.add(bare + " " + value);
                }
                return;
            }
            if (key != null && !ipsIn(key).isEmpty()) {
                leaks.add(where + " key " + keyPath);
            }
            if (value != null && !ipsIn(value).isEmpty()) {
                leaks.add(where + " " + keyPath + ": " + value);
            }
            if (value != null) {
                for (String ip : SEEDED) {
                    if (value.contains(ip)) {
                        leaks.add(where + " " + keyPath + " contains " + ip);
                    }
                }
            }
        });
    }

    private interface Visitor {
        /** {@code key}: the member name, or {@code null} in arrays; {@code value}: a string value, or null. */
        void visit(String path, String key, String value);
    }

    private static void walk(JsonElement e, String path, Visitor v) {
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
                String p = path + "." + m.getKey();
                JsonElement x = m.getValue();
                v.visit(p, m.getKey(), x.isJsonPrimitive() && x.getAsJsonPrimitive().isString() ? x.getAsString() : null);
                walk(x, p, v);
            }
        } else if (e.isJsonArray()) {
            int i = 0;
            for (JsonElement x : e.getAsJsonArray()) {
                String p = path + "[" + i++ + "]";
                if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isString()) {
                    v.visit(p, null, x.getAsString());
                }
                walk(x, p, v);
            }
        }
    }

    /** The route ({@code "METHOD pattern"}) that answers {@code method path}, as the router picks it. */
    private String routeOf(String method, String path) {
        String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String best = null;
        PathPattern bestPattern = null;
        for (String route : router.routes()) {
            int space = route.indexOf(' ');
            if (!route.substring(0, space).equals(method)) {
                continue;
            }
            PathPattern p = PathPattern.parse(route.substring(space + 1));
            if (p.match(bare) != null && (bestPattern == null || p.compareTo(bestPattern) < 0)) {
                best = route;
                bestPattern = p;
            }
        }
        return best == null ? method + " (no route) " + bare : best;
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String cookie(HttpResponse<String> r, String name) {
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith(name + "=")) {
                return c.substring(0, c.indexOf(';'));
            }
        }
        throw new AssertionError("no " + name + " cookie: " + r.headers().map());
    }
}
