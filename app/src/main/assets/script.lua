lbl = ui.NewLabel("...")
local function hello(control)
    rc = ptp.sendOperation(0x1001, {})
    lbl:SetText("Err: " .. tostring(rc.error)
    .. "\n" .. "Code: " .. tostring(rc.code))
    exit() -- mark this script as dead
end
x = ui.NewWindow("Lua Demo", 100, 100, false)
box = ui.NewVerticalBox()
btn = ui.NewButton("Do stuff")
btn:OnClicked(hello)
box:Append(ui.NewLabel("This is just a PoC."))
box:Append(ui.NewLabel("Expect more interesting things soon."))
box:Append(btn)
box:Append(lbl)
x:SetChild(box)
x:Show()