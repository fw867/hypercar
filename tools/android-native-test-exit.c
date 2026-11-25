/* Only preload into standalone Android native tests, never the application.
 * Codec2's process-global buffer-pool objects own detached maintenance threads
 * which can outlive their mutexes during normal libc exit. libtest has already
 * dropped its test futures/runtimes before calling exit; terminate the process
 * without running platform global destructors. Preserve failures and signals.
 * Run without this library when auditing process-global destructors themselves.
 */
#include <stdio.h>
#include <stdlib.h>

__attribute__((noreturn)) void exit(int status) {
    fflush(NULL);
    _Exit(status);
}
