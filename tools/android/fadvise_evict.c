// Drops files from the Linux page cache (posix_fadvise POSIX_FADV_DONTNEED) without root, so a
// test can start with the game data "cold" as after a reboot. Also reports how much of each tree
// was cached before (mincore over a read-only mapping).
// Build: tools/android/build_fadvise_evict.sh; run: /data/local/tmp/fadvise_evict [-n] DIR...
//   -n: only report the cached share, do not evict.
#define _GNU_SOURCE
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

static int g_report_only = 0;
static unsigned long long g_files = 0, g_bytes = 0, g_cached = 0;

static void handle_file(const char* path, off_t size) {
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return;
  ++g_files;
  g_bytes += (unsigned long long)size;
  if (size > 0) {
    void* map = mmap(NULL, (size_t)size, PROT_READ, MAP_SHARED, fd, 0);
    if (map != MAP_FAILED) {
      long page = sysconf(_SC_PAGESIZE);
      size_t pages = ((size_t)size + (size_t)page - 1) / (size_t)page;
      unsigned char* vec = malloc(pages);
      if (vec && mincore(map, (size_t)size, vec) == 0) {
        for (size_t i = 0; i < pages; ++i) {
          if (vec[i] & 1) g_cached += (unsigned long long)page;
        }
      }
      free(vec);
      munmap(map, (size_t)size);
    }
    if (!g_report_only) posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
  }
  close(fd);
}

static void walk(const char* dir) {
  DIR* d = opendir(dir);
  if (!d) return;
  struct dirent* e;
  char path[4096];
  while ((e = readdir(d))) {
    if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
    snprintf(path, sizeof(path), "%s/%s", dir, e->d_name);
    struct stat st;
    if (lstat(path, &st)) continue;
    if (S_ISDIR(st.st_mode)) {
      walk(path);
    } else if (S_ISREG(st.st_mode)) {
      handle_file(path, st.st_size);
    }
  }
  closedir(d);
}

int main(int argc, char** argv) {
  int i = 1;
  if (i < argc && !strcmp(argv[i], "-n")) {
    g_report_only = 1;
    ++i;
  }
  for (; i < argc; ++i) walk(argv[i]);
  printf("%llu files, %.1f MB, %.1f MB were in the page cache%s\n", g_files, g_bytes / 1048576.0,
         g_cached / 1048576.0, g_report_only ? "" : "; evicted");
  return 0;
}
