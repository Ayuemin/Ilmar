/* Ilmar process launcher. A separate session lets cancellation stop the process group. */
#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
int main(int argc, char **argv) {
    if (argc < 2 || setsid() < 0) return 125;
    printf("ILMAR_PROCESS_GROUP=%ld\n", (long)getpid());
    fflush(stdout);
    execv(argv[1], argv + 1);
    perror("execv");
    return 126;
}
