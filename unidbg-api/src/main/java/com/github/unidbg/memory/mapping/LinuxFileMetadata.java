package com.github.unidbg.memory.mapping;

import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.sun.jna.Native;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

/** Uses the same host stat ABI as file mapping, without opening a description. */
public final class LinuxFileMetadata {
    public static FileQueryResult<FileMetadata> query(Path path, boolean followLinks) {
        LinuxMappingCalls.FileStat stat = new LinuxMappingCalls.FileStat();
        int rc = followLinks ? LinuxMappingCalls.FILES.stat(path.toString(), stat)
                : LinuxMappingCalls.FILES.lstat(path.toString(), stat);
        return rc < 0 ? FileQueryResult.failed(Native.getLastError()) : FileQueryResult.success(snapshot(stat));
    }

    public static FileQueryResult<FileMetadata> query(int fd) {
        LinuxMappingCalls.FileStat stat = new LinuxMappingCalls.FileStat();
        return LinuxMappingCalls.FILES.fstat(fd, stat) < 0 ? FileQueryResult.failed(Native.getLastError())
                : FileQueryResult.success(snapshot(stat));
    }

    private static FileMetadata snapshot(LinuxMappingCalls.FileStat stat) {
        return FileMetadata.builder().dev(stat.device).ino(stat.inode).nlink(Math.toIntExact(stat.links))
                .mode(stat.mode).uid(stat.uid).gid(stat.gid).rdev(stat.rdev).size(stat.size)
                .blksize(Math.toIntExact(stat.blockSize)).blocks(stat.blocks)
                .atime(time(stat.times, 0)).mtime(time(stat.times, 2)).ctime(time(stat.times, 4)).build();
    }

    private static FileTime time(long[] times, int index) {
        return FileTime.from(Instant.ofEpochSecond(times[index], times[index + 1]));
    }

    private LinuxFileMetadata() { }
}
