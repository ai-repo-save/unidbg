# Native file mappings

## Runtime

Host-backed ordinary file mappings require a Linux x86_64 host with 4096-byte pages and the Unicorn2 backend. ARM32 and ARM64 guests share the mapping implementation. Other backends and host platforms do not acquire this capability through the anonymous `Backend.mem_map` API.

`HostMemoryBackend.mem_map_host` uses Unicorn 2.1.4's public `uc_mem_map_ptr` API. The buffer must be direct and host-readable/writable, independently of guest permissions. The caller owns its storage until guest unmapping or backend destruction. A buffer's capacity defines the mapped range; position and limit do not select a subrange.

`MappingSource` binds its host file resource during the ordinary file open operation. Mapping uses that resource rather than reopening a pathname or changing the file position. `MAP_SHARED` uses the host kernel's shared file pages; file reads, file writes and overlapping shared views observe the same backing. `MAP_PRIVATE` uses host copy-on-write mappings. Private writes remain independent of the file and other views; visibility of subsequent file changes in private views is not a promised isolation property.

Guest access permissions remain separate from host mapping capabilities. A guest read-only descriptor can create a writable private view or a read-only shared view of a host-writable file. A read-only host source supports private mappings. Shared views require host read/write backing; passing a read-only host pointer violates Unicorn's public API contract.

## Regions and lifetime

`MemoryMap` retains flags, file offset, backing region and write eligibility from the creating guest descriptor. Splitting a mapping for `mprotect` or partial `munmap` preserves these properties. Protection changes preflight the complete request; a shared view created from a read-only descriptor cannot acquire write permission after splitting or descriptor closure. `msync` resolves guest ranges through these records and returns raw Linux results. A missing mapped page returns `-ENOMEM` before any region is synchronized.

`MAP_FIXED` prepares a file view before replacing overlapping guest ranges. Uncommitted views are released on failure. Closing a source or guest descriptor leaves its existing regions alive. The last guest slice releases its region. Emulator shutdown closes file resources, unmaps remaining host-backed guest slices and releases their regions before destroying the backend.

`MADV_DONTNEED` on host-backed file regions delegates to host `madvise`: private copy-on-write pages reload from the file, while shared file contents remain intact. Anonymous-private zero filling belongs to the memory advice implementation; unknown backing and anonymous-shared mappings are not treated as private anonymous storage.

## File length and capability boundaries

The final partial file page is supported. Its bytes after EOF initially read as zero, and writing that tail does not extend the file. Mapping a whole page beyond EOF, including mapping an empty file, requires guest SIGBUS delivery and remains explicitly unsupported.

All truncation of a bound file must use `MappingSource.truncate`. The mapping registry identifies live regions by the host file's device and inode. Mapping registration, region release, truncation checks and the original-fd `ftruncate` operation use one lock. This coordination survives independent opens, pathname replacement, renaming and source closure.

Growth is supported. Shrinking is supported when every live region still ends on a page containing file data, including shrinking within the final mapped page. A shrink that would leave a whole mapped page beyond EOF raises `UnsupportedOperationException` before changing the host file. This protects the host process; it does not implement Linux guest SIGBUS semantics. Region leases cover their complete host views until release, including views retained by surviving guest slices.

Executable host-backed file mappings and `mprotect` upgrades to executable permissions remain explicitly unsupported pending alias-aware JIT invalidation. Host-backed file `mremap` is currently unsupported; it requires preservation of backing identity rather than anonymous copying. The existing int-sized mapping length interface remains in effect; file offsets are byte offsets represented as `long` values, with ARM32 mmap2 page offsets widened before shifting.

## Explicit integration validation

`NativeFileMappingIntegrationTest` is opt-in and parameterized by ARM32/ARM64. It executes guest stores and Linux SVC calls, then independently reads files and host `/proc` resources. It covers shared aliases, file I/O coherence, private isolation, read-only guest descriptors, partial EOF pages, offsets above 4 GiB, mapping splits, synchronization, discard, descriptor closure, pathname replacement, safe resizing and shutdown release. The unsupported-truncation assertion checks the implementation boundary and host-file preservation, not Linux SIGBUS conformance.

Use Linux x86_64 and JDK 21. Prepare the matching generated native resources through `scripts/release.py --build-only`, supplying the unchanged Unicorn 2.1.4 commit `8028ec436f2d9376525352dd38ed9ed6b9f6be10` and the collector's dependency classpath file. Preparation records an unpublished build identity and stages Unicorn2 under `backend/unicorn2/target/generated-resources/natives/linux_64/`. An older packaged JNI library lacks the direct-buffer entry point. Run the explicit test afterward without cleaning the generated resources.

```sh
uv run --script scripts/release.py --build-only \
  --java-home /usr/lib/jvm/java-21-openjdk-amd64 \
  --unicorn-source /absolute/unchanged-unicorn-source \
  --consumer-classpath-file /absolute/collector-runtime-dependencies.txt \
  --offline
./mvnw -pl unidbg-android -am -Dmaven.test.skip=false \
  -Dtest=NativeFileMappingIntegrationTest -DfailIfNoTests=false test
```

Kernel contracts: https://man7.org/linux/man-pages/man2/mmap.2.html, https://man7.org/linux/man-pages/man2/msync.2.html, https://man7.org/linux/man-pages/man2/mprotect.2.html, https://man7.org/linux/man-pages/man2/madvise.2.html and https://man7.org/linux/man-pages/man2/ftruncate.2.html.

Unicorn API: https://github.com/unicorn-engine/unicorn/blob/2.1.4/include/unicorn/unicorn.h.
