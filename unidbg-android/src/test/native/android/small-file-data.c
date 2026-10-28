/*
 * From the repository root (NDK 28, JDK 21):
 * mkdir -p unidbg-android/target/small-file-data
 * NDK_BIN="$HOME/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
 * "$NDK_BIN/aarch64-linux-android23-clang" -std=c11 -Wall -Wextra -Werror -fPIC -shared -Wl,-soname,libsmall-file-data.so unidbg-android/src/test/native/android/small-file-data.c -o unidbg-android/target/small-file-data/libsmall-file-data-arm64.so
 * "$NDK_BIN/armv7a-linux-androideabi23-clang" -std=c11 -Wall -Wextra -Werror -fPIC -shared -Wl,-soname,libsmall-file-data.so unidbg-android/src/test/native/android/small-file-data.c -o unidbg-android/target/small-file-data/libsmall-file-data-arm32.so
 * JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 "$HOME/opt/apache-maven-3.9.9/bin/mvn" -o -pl unidbg-android -am -Dmaven.test.skip=false -Dtest=SmallFileDataIntegrationTest -DfailIfNoTests=false -Dunidbg.smallFileDataLibrary64="$PWD/unidbg-android/target/small-file-data/libsmall-file-data-arm64.so" -Dunidbg.smallFileDataLibrary32="$PWD/unidbg-android/target/small-file-data/libsmall-file-data-arm32.so" test
 */
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <unistd.h>

int directory_results[6];
unsigned char directory_bytes[4096];
int file_results[10];
unsigned char file_bytes[8];

int directory_probe(const char *path) {
    int fd = open(path, O_RDONLY | O_DIRECTORY);
    if (fd < 0) return -1;
    errno = 0;
    directory_results[0] = syscall(SYS_getdents64, fd, directory_bytes, 1);
    directory_results[1] = errno;
    directory_results[2] = syscall(SYS_getdents64, fd, directory_bytes, 24);
    if (directory_results[2] < 0) {
        close(fd);
        return -2;
    }
    int used = directory_results[2];
    int result;
    do {
        if ((size_t) used + 64 > sizeof(directory_bytes)) {
            close(fd);
            return -3;
        }
        result = syscall(SYS_getdents64, fd, directory_bytes + used, 64);
        if (result > 0) used += result;
    } while (result > 0);
    directory_results[3] = result;
    directory_results[4] = used;
    directory_results[5] = syscall(SYS_getdents64, fd, directory_bytes + used, 64);
    return close(fd);
}

int file_probe(const char *path, int length) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    file_results[0] = read(fd, file_bytes, 2);
    file_results[1] = pread64(fd, file_bytes + 2, 3, 4);
    file_results[2] = pread64(fd, file_bytes + 5, 1, INT64_C(0x100000000));
    errno = 0;
    file_results[3] = pread64(fd, file_bytes + 5, 1, -1);
    file_results[4] = errno;
    file_results[5] = pread64(fd, file_bytes + 5, 1, length);
    file_results[6] = read(fd, file_bytes + 5, 2);
    file_results[7] = pread64(fd, file_bytes + 7, 1, length - 1);
    file_results[8] = lseek(fd, 0, SEEK_CUR);
    file_results[9] = close(fd);
    return 0;
}
