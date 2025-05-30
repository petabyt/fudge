#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
//#ifdef WIN32
//	#include <winsock2.h>
//	#include <ws2tcpip.h>
//#else
//	#include <arpa/inet.h>
//#endif
#include <libpict.h>
#include <cl_stuff.h>
#include <fuji.h>
#include <app.h>
#include "desktop.h"
#include <fp.h>

int fudge_ui(void);

void ptp_verbose_log(char *fmt, ...) {
#ifdef FUDGE_DEBUG
	printf("PTP: ");
	va_list args;
	va_start(args, fmt);
	vprintf(fmt, args);
	va_end(args);
#endif
}

__attribute__ ((noreturn))
void ptp_panic(char *fmt, ...) {
	printf("PTP abort: ");
	va_list args;
	va_start(args, fmt);
	vprintf(fmt, args);
	va_end(args);
	puts("");
	fflush(stdout);
	abort();
}

char *app_get_client_name(void) {
	return strdup("desktop");
}

int app_check_thread_cancel(void) {
	// TODO: check signal
	return 0;
}

int fuji_discover_ask_connect(void *arg, struct DiscoverInfo *info) {
	// Ask if we want to connect?
	return 1;
}

int fuji_discovery_check_cancel(void *arg) {
	return 0;
}

void fuji_discovery_update_progress(void *arg, enum DiscoverUpdateMessages progress) {
}

void app_increment_progress_bar(int read) {
	// ...
}

void app_report_download_speed(long time, size_t size) {
	// ...
}

void tester_log(char *fmt, ...) {
	char buffer[512];
	va_list args;
	va_start(args, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, args);
	va_end(args);
	printf("LOG: %s\n", buffer);
}

void tester_fail(char *fmt, ...) {
	char buffer[512];
	va_list args;
	va_start(args, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, args);
	va_end(args);
	printf("FAIL: %s\n", buffer);
}

void app_update_connected_status(int connected) {
	
}

static int help(void) {
	printf("Fudge 0.1.0\n");
	printf("Compilation date: " __DATE__ "\n");
	printf("  --list\n");
	printf("    List all PTP devices connected to this computer\n");

	printf("  --dev <device number>\n");
	printf("    Select a device to connect to instead of choosing the first available one.\n");

	printf("  --raw <input raf> <output jpeg path> <profile file>\n");
	printf("    Does a Raw conversion based on information parsed from a FP1/FP2/FP3 file.\n");

	printf("  --backup <output dat path>\n");
	printf("    Backs up camera settings to file.\n");

	printf("  --restore <input dat path>\n");
	printf("    Load camera settings from file.\n");

	printf("  --dump-usb\n");
	printf("    Dump all info on a camera. If something goes wrong you may want to send this info to developers.\n");

	printf("  --script <filename>\n");
	printf("    Execute a Lua script using fudge bindings\n");

	printf("  --parse-fp <filename>\n");
	printf("    Parse and dump a FP1/FP2/FP3 file\n");
	return 0;
}

int main(int argc, char **argv) {
	int devnum = -1;
	for (int i = 1; i < argc; i++) {
		// Typical camlib CLI stuff
		if (!strcmp(argv[i], "--list")) {
			return ptp_list_devices();
		}
		if (!strcmp(argv[i], "--dev")) {
			if (i + 1 >= argc) {
				printf("Invalid argument\n");
				return -1;
			}
			devnum = strtol(argv[i + 1], NULL, 10);
			i++;
			continue;
		}
		if (!strcmp(argv[i], "--info")) {
			int dev_id = 0;
			if (i + 2 <= argc) dev_id = atoi(argv[i + 1]);
			return ptp_dump_device(dev_id);
		}
		if (!strcmp(argv[i], "--script")) {
			return fuji_connect_run_script(devnum, argv[i + 1]);
		}
		if (!strcmp(argv[i], "--parse-fp")) {
			if (i + 1 >= argc) {
				printf("Invalid argument\n");
				return -1;
			}
			struct FujiProfile fp;
			int rc = fp_parse_fp1(argv[i + 1], &fp);
			if (rc) {
				printf("Error parsing profile: '%s'\n", fp_get_error());
				return rc;
			}
			fp_dump_struct(stdout, &fp);
			printf("\n");

			return 0;
		}
		if (!strcmp(argv[i], "--raw")) {
			if (i + 3 >= argc) {
				printf("%s --raw <input> <output> <profile>\n", argv[0]);
				return -1;
			}
			return fudge_process_raf(devnum, argv[i + 1], argv[i + 2], argv[i + 3]);
		}
		if (!strcmp(argv[i], "--test-wifi")) {
			int rc = fudge_test_all_cameras();
			plat_dbg("Result: %d\n", rc);
			return rc;
		}
		if (!strcmp(argv[i], "--dump-usb")) {
			return fudge_dump_usb(devnum);
		}
		if (!strcmp(argv[i], "--test-discovery")) {
			fuji_test_discovery(ptp_new(PTP_USB));
			return 1;
		}
		if (!strcmp(argv[i], "--help")) {
			return help();
		}
		if (!strcmp(argv[i], "--backup")) {
			if (i + 1 >= argc) {
				printf("%s --backup <backup file>\n", argv[0]);
				return -1;
			}
			fudge_download_backup(devnum, argv[i + 1]);
			return -1;
		}
		if (!strcmp(argv[i], "--restore")) {
			if (i + 1 >= argc) {
				printf("%s --restore <backup file>\n", argv[0]);
				return -1;
			}
			fudge_restore_backup(devnum, argv[i + 1]);
			return -1;
		}

		printf("Invalid arg '%s'\n", argv[i]);
		printf("Use --help for a list of commands\n");
		return -1;
	}

	return fudge_ui();
}
