package io.siggi.togethertunes.output;

import io.siggi.togethertunes.media.SampleIndex;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * {@link SampleData} for the files an index refers to, all in one directory (an item directory, or
 * the cards directory). Each file is opened on first use and read with positional reads
 * ({@code FileChannel.read(buf, pos)}), so concurrent readers don't disturb each other.
 */
public final class FileSampleData implements SampleData, AutoCloseable {
    private final Path dir;
    private final List<String> files;
    private final FileChannel[] channels;
    private boolean closed;

    public FileSampleData(Path dir, SampleIndex index) {
        this.dir = dir;
        this.files = index.files();
        this.channels = new FileChannel[files.size()];
    }

    @Override
    public byte[] read(int fileRef, long offset, int size) throws IOException {
        if (fileRef < 0 || fileRef >= channels.length) {
            throw new IOException("no file-ref " + fileRef);
        }
        if (offset < 0 || size < 0) {
            throw new IOException("bad read " + offset + "+" + size);
        }
        FileChannel ch = channel(fileRef);
        ByteBuffer buf = ByteBuffer.allocate(size);
        while (buf.hasRemaining()) {
            if (ch.read(buf, offset + buf.position()) < 0) {
                throw new EOFException(files.get(fileRef) + " ends before " + (offset + size));
            }
        }
        return buf.array();
    }

    private synchronized FileChannel channel(int fileRef) throws IOException {
        if (closed) {
            throw new IOException("closed");
        }
        FileChannel ch = channels[fileRef];
        if (ch == null) {
            ch = FileChannel.open(dir.resolve(files.get(fileRef)), StandardOpenOption.READ);
            channels[fileRef] = ch;
        }
        return ch;
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        IOException first = null;
        for (int i = 0; i < channels.length; i++) {
            if (channels[i] != null) {
                try {
                    channels[i].close();
                } catch (IOException e) {
                    if (first == null) {
                        first = e;
                    }
                }
                channels[i] = null;
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
