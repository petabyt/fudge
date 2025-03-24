#include <imgui.h>

extern "C" {
#include "im.h"
}

static __thread struct PrivCache {
	int combo_index;
}priv = {0};

void im_begin_disabled(void) {
	ImGui::BeginDisabled();
}
void im_end_disabled(void) {
	ImGui::EndDisabled();
}

int im_tab() {
	return ImGui::BeginTabBar("...") == true;
}
int im_add_tab_item(const char *title) {
	return ImGui::BeginTabItem(title) == true;
}
int im_end_tab_item(const char *title) {
	return ImGui::BeginTabItem(title) == true;
}
void im_end_tab() {
	ImGui::EndTabBar();
}

int im_combo_box(const char *label, const char *preview) {
	int rc = ImGui::BeginCombo(label, preview, 0);
	if (rc) {
		priv.combo_index = 0;
	} else {
		priv.combo_index = -1;
	}
	return rc;
}
int im_add_combo_box_item(const char *label, int *selected) {
	if (priv.combo_index == -1) return 0;
	const bool is_selected = ((*selected) == priv.combo_index);
	if (ImGui::Selectable(label, is_selected)) {
		(*selected) = priv.combo_index;
	}

	priv.combo_index++;

	if (is_selected) {
		ImGui::SetItemDefaultFocus();
		return 1;
	}
	return 0;
}
void im_end_combo_box(void) {
	if (priv.combo_index == -1) return;
	ImGui::EndCombo();
}

int im_button(const char *label) {
	return ImGui::Button(label);
}
int im_label(const char *label) {
	ImGui::Text(label);
	return 0;
}
int im_window(const char *name, int width, int height, int flags) {
	return ImGui::Begin(name);
}
void im_end_window(void) {
	ImGui::End();
}
