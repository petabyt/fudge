#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#ifdef WIN32
	#include <winsock2.h>
	#include <ws2tcpip.h>
#else
	#include <arpa/inet.h>
#endif
#include "app.h"
#include "fuji.h"

#define FUJI_AUTOSAVE_REGISTER 51542
#define FUJI_AUTOSAVE_CONNECT 51541
#define FUJI_AUTOSAVE_NOTIFY 51540

static int connect_to_notify_server(char *ip, int port) {
	int server_fd, client_fd;
	struct sockaddr_in server_addr;

	server_fd = socket(AF_INET, SOCK_STREAM, 0);
	int rc = app_bind_socket_wifi(server_fd);
	if (rc) return -1;
	if (server_fd < 0) {
		plat_dbg("Failed to create TCP socket");
		return -1;
	}

	struct sockaddr_in sa;
	memset(&sa, 0, sizeof(sa));
	sa.sin_family = AF_INET;
	sa.sin_port = htons(port);
	if (inet_pton(AF_INET, ip, &(sa.sin_addr)) <= 0) {
		plat_dbg("inet_pton");
		return -1;
	}

	if (connect(server_fd, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
		if (errno != EINPROGRESS) {
			plat_dbg("Connect fail");
			return -1;
		}
	}

	fd_set fdset;
	FD_ZERO(&fdset);
	FD_SET(server_fd, &fdset);
	struct timeval tv;
	tv.tv_sec = 1;
	tv.tv_usec = 0;

	if (select(server_fd + 1, NULL, &fdset, NULL, &tv) == 1) {
		int so_error = 0;
		socklen_t len = sizeof(so_error);
		if (getsockopt(server_fd, SOL_SOCKET, SO_ERROR, &so_error, &len) < 0) {
			plat_dbg("Sockopt fail");
			return -1;
		}

		if (so_error == 0) {
			plat_dbg("notify server: Connection established");
			return server_fd;
		}
	} else {
		plat_dbg("select failed to connect");
	}

	close(server_fd);

	return 0;
}

static int start_invite_server(struct DiscoverInfo *info, int port) {
	int server_fd, client_fd;
	struct sockaddr_in server_addr, client_addr;
	socklen_t client_addr_len = sizeof(client_addr);

	server_fd = socket(AF_INET, SOCK_STREAM, 0);
	app_bind_socket_wifi(server_fd);
	if (server_fd < 0) {
		plat_dbg("Failed to create TCP socket");
		return -1;
	}

	server_addr.sin_family = AF_INET;
	server_addr.sin_port = htons(port);
	server_addr.sin_addr.s_addr = INADDR_ANY;

	int yes = 1;
	if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(int)) < 0) {
		plat_dbg("Failed to set sockopt");
		return -1;
	}

	if (bind(server_fd, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
		plat_dbg("upnp: Binding failed");
		return -1;
	}

	// Listen for incoming connections
	if (listen(server_fd, 5) < 0) {
		plat_dbg("upnp: Listening failed");
		return -1;
	}

	plat_dbg("invite server is listening...");

	client_fd = accept(server_fd, (struct sockaddr*)&client_addr, &client_addr_len);
	if (client_fd < 0) {
		plat_dbg("invite server: Accepting connection failed");
		return -1;
	}

	plat_dbg("invite server: Connection accepted from %s:%d", inet_ntoa(client_addr.sin_addr), ntohs(client_addr.sin_port));

	// We don't really care about this info
	char buffer[1024];
	int rc = recv(client_fd, buffer, sizeof(buffer) - 1, 0);
	if (rc < 0) {
		perror("recv fail");
		return -1;
	}
	buffer[rc] = '\0';

	char *saveptr;
	char *delim = " :\r\n";
	char *cur = strtok_r(buffer, delim, &saveptr);
	while (cur != NULL) {
		if (!strcmp(cur, "DSCNAME")) {
			cur = strtok_r(NULL, delim, &saveptr);
			if (cur == NULL) return -1;
			strncpy(info->camera_name, cur, sizeof(info->camera_name));
		} else if (!strcmp(cur, "DSCMODEL")) {
			cur = strtok_r(NULL, delim, &saveptr);
			if (cur == NULL) return -1;
			strncpy(info->camera_model, cur, sizeof(info->camera_model));
		}
		cur = strtok_r(NULL, delim, &saveptr);
	}

	char *resp;
	if (port == FUJI_AUTOSAVE_REGISTER) {
		resp = 
			"HTTP/1.1 200 OK\r\n"
			"FOLDER: guest\r\n"
			"ServiceName: PCAUTOSAVE/1.0\r\n";
	} else if (port == FUJI_AUTOSAVE_CONNECT) {
		resp = "HTTP/1.1 200 OK\r\n";
	} else {
		return -1;
	}

	rc = send(client_fd, resp, strlen(resp), 0);
	if (rc < 0) {
		plat_dbg("Failed to send response");
		return -1;
	}

	close(client_fd);
	close(server_fd);

	return 0;
}

// TODO: Respond to any connection
static int respond_to_datagram(char *greeting, struct DiscoverInfo *info) {
	memset(info, 0, sizeof(struct DiscoverInfo));

	char *saveptr;
	char *delim = " :\r\n";
	char *cur = strtok_r(greeting, delim, &saveptr);
	while (cur != NULL) {
		if (!strcmp(cur, "DISCOVER")) {
			cur = strtok_r(NULL, delim, &saveptr);
			if (cur == NULL) return -1;
			strcpy(info->client_name, cur);
		} else if (!strcmp(cur, "DSCADDR")) {
			cur = strtok_r(NULL, delim, &saveptr);
			if (cur == NULL) return -1;
			plat_dbg("Client IP: %s\n", cur);
			strcpy(info->camera_ip, cur);
		}
		cur = strtok_r(NULL, delim, &saveptr);
	}

	char *client_name = "desktop";

	char response[] =
		"NOTIFY * HTTP/1.1\r\n"
		"HOST: %s:%d\r\n"
		"IMPORTER: %s\r\n";

	char notify[512];
	sprintf(notify, response, info->camera_ip, FUJI_AUTOSAVE_NOTIFY, client_name);

	int fd = connect_to_notify_server(info->camera_ip, FUJI_AUTOSAVE_NOTIFY);
	if (fd <= 0) {
		plat_dbg("Failed to connect to notify server: %d", fd);
		return -1;
	}

	send(fd, notify, strlen(notify), 0);

	char repsonse[512];
	int len = recv(fd, response, sizeof(response), 0);
	response[len] = '\0';

	close(fd);

	return 0;
}

static int accept_register(struct DiscoverInfo *info, char *greeting) {
	int rc = respond_to_datagram(greeting, info);
	if (rc) return rc;

	rc = start_invite_server(info, FUJI_AUTOSAVE_REGISTER);
	if (rc) return rc;

	plat_dbg("Finished registering");

	return 0;
}

static int accept_connect(struct DiscoverInfo *info, char *greeting) {
	int rc = respond_to_datagram(greeting, info);
	if (rc) return rc;

	rc = start_invite_server(info, FUJI_AUTOSAVE_CONNECT);
	if (rc) return rc;

	plat_dbg("Finished connecting");

	return 0;
}

static int open_dgram_socket(int port) {
	struct sockaddr_in addr;

	int fd = socket(AF_INET, SOCK_DGRAM, 0);
	int rc = app_bind_socket_wifi(fd);
	if (rc) return -1;
	if (fd < 0) {
		plat_dbg("socket");
		return -1;
	}

	memset(&addr, 0, sizeof(addr));
	addr.sin_family = AF_INET;
	addr.sin_addr.s_addr = htonl(INADDR_ANY);
	addr.sin_port = htons(port);
	plat_dbg("Binding to %d", port);
	rc = bind(fd, (struct sockaddr *)&addr, sizeof(addr));
	if (rc < 0) {
		plat_dbg("bind");
		close(fd);
		return -1;
	}

	return fd;
}

int fuji_discover_thread(struct DiscoverInfo *info, char *client_name) {
	memset(info, 0, sizeof(struct DiscoverInfo));
	int reg_fd = open_dgram_socket(FUJI_AUTOSAVE_REGISTER);
	if (reg_fd <= 0) {
		plat_dbg("Error connect register");
		return reg_fd;
	}
	int con_fd = open_dgram_socket(FUJI_AUTOSAVE_CONNECT);
	if (con_fd <= 0) {
		plat_dbg("Error connect svr");
		return con_fd;
	}

	struct sockaddr_in clnt_addr;
	socklen_t clnt_addr_size;

	int rc = 0;

	char greeting[1024];
	while (1) {
		struct timeval tv;
		tv.tv_sec = 1;
		tv.tv_usec = 0;
	
		fd_set fdset;
		FD_ZERO(&fdset);
		FD_SET(reg_fd, &fdset);
		FD_SET(con_fd, &fdset);

		int n = select((reg_fd > con_fd ? reg_fd : con_fd) + 1, &fdset, NULL, NULL, &tv);
		if (n < 0) {
			plat_dbg("select: %d", n);
			rc = -1;
			break;
		}

		if (n != 0) {
			plat_dbg("Received something!");
		}

		if (FD_ISSET(reg_fd, &fdset)) {
			int len = recvfrom(reg_fd, greeting, sizeof(greeting) - 1, 0, NULL, NULL);
			if (len <= 0) {
				plat_dbg("recvfrom: %d", len);
				rc = -1;
				break;
			}
			greeting[len] = '\0';
			rc = accept_register(info, greeting);
			if (rc == 0) rc = FUJI_D_REGISTERED;
			break;
		}
		if (FD_ISSET(con_fd, &fdset)) {
			int len = recvfrom(con_fd, greeting, sizeof(greeting) - 1, 0, NULL, NULL);
			if (len <= 0) {
				plat_dbg("recvfrom: %d", len);
				rc = -1;
				break;
			}
			greeting[len] = '\0';
			accept_connect(info, greeting);
			if (rc == 0) rc = FUJI_D_GO_PTP;
			break;
		}

		// idle, TODO cancel
	}

	close(con_fd);
	close(reg_fd);
	return rc;
}
