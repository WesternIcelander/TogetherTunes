package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GuestKeysTest {
    @Test
    void ipv4IsTheFullAddress() {
        assertEquals("203.0.113.7", GuestKeys.ipKey("203.0.113.7"));
        assertEquals("203.0.113.7", GuestKeys.ipKey(" 203.0.113.7 "));
        assertEquals("203.0.113.7", GuestKeys.ipKey("::ffff:203.0.113.7"));
        assertNotEquals(GuestKeys.ipKey("203.0.113.7"), GuestKeys.ipKey("203.0.113.8"));
    }

    @Test
    void ipv6IsThe64Prefix() {
        assertEquals("2001:db8:1:2::/64", GuestKeys.ipKey("2001:db8:1:2::1"));
        assertEquals("2001:db8:1:2::/64", GuestKeys.ipKey("2001:0DB8:0001:0002:ffff:eeee:dddd:cccc"));
        assertEquals("2001:db8:1:2::/64", GuestKeys.ipKey("[2001:db8:1:2::5]"));
        assertEquals("fe80:0:0:0::/64", GuestKeys.ipKey("fe80::1%en0"));
        assertEquals("0:0:0:0::/64", GuestKeys.ipKey("::1"));
        assertNotEquals(GuestKeys.ipKey("2001:db8:1:2::1"), GuestKeys.ipKey("2001:db8:1:3::1"));
    }

    @Test
    void hashIsSha256OfTheKey() {
        assertEquals(Tokens.hash("2001:db8:1:2::/64"), GuestKeys.ipKeyHash("2001:db8:1:2:aaaa::1"));
        assertEquals(Tokens.hash("198.51.100.1"), GuestKeys.ipKeyHash("198.51.100.1"));
    }

    @Test
    void nonLiteralsAreRejectedWithoutLookup() {
        assertNull(GuestKeys.ipKey(null));
        assertNull(GuestKeys.ipKey(""));
        assertNull(GuestKeys.ipKey("localhost"));
        assertNull(GuestKeys.ipKey("example.com"));
        assertNull(GuestKeys.ipKey("127.1"));
        assertNull(GuestKeys.ipKey("256.0.0.1"));
        assertNull(GuestKeys.ipKey("1.2.3.4.5"));
        assertNull(GuestKeys.ipKey("2001:db8::g"));
        assertNull(GuestKeys.ipKey("1:2:3:4:5:6:7:8:9"));
        assertNull(GuestKeys.ipKeyHash("nope"));
    }
}
