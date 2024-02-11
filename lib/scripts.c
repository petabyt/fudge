// Scripts UI layouts
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>

#include <camlib.h>
#include <camlua.h>
#include <ui.h>

static uiMultilineEntry *script_box = NULL;

int cam_lua_setup(lua_State *L) {
	luaL_requiref(L, "ui", luaopen_libuilua, 1);
	return 0;
}

static void on_click(uiButton *b, void *dat) {
	char *file = uiMultilineEntryText(script_box);
	if (cam_run_lua_script_async(file) < 0) {
		uiToast(cam_lua_get_error());
	}

	uiFreeText(file);
}

uiBox *fudge_scripts_screen() {
	uiBox *box = uiNewVerticalBox();
	uiBoxSetPadded(box, 1);

	char *file = libu_get_txt_file(uiAndroidGetEnv(), uiAndroidGetCtx(), "script.lua");

	script_box = uiNewMultilineEntry();
	uiMultilineEntrySetText(script_box, file);
	uiBoxAppend(box, uiControl(script_box), 0);

	free(file);

	uiButton *b = uiNewButton("Run script");
	uiButtonOnClicked(b, on_click, NULL);
	uiBoxAppend(box, uiControl(b), 0);

	uiBoxAppend(box, uiControl(uiNewLabel("Lua plugins coming soon")), 0);

	uiBoxAppend(box, uiControl(uiNewLabel("Planned features:\n"
						"- Scripts can have custom UIs\n"
						"- Scripts can run as background tasks\n"
						"- Automate things and workflows\n"
						"- Bleeding edge experimental features!\n"
						"- Buffer overflows!")), 0);
	return box;
}

int fuji_scripts_screen(uiWindow *win) {
	uiWindowSetChild(win, uiControl(fudge_scripts_screen()));
	return 0;
}