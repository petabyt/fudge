package dev.danielc.fujiapp;
import org.json.JSONObject;

public class Backend {
    static {
        System.loadLibrary("fujiapp");
    }

    public native static void cInit(Backend b, Conn c);
    public native static String cTestFunc();
    public native static int cPtpFujiInit();
    public native static int cPtpFujiWaitUnlocked();
    public native static String cPtpRun(String req);

    public static JSONObject run(String req) throws Exception {
        String resp = cPtpRun(req);
        try {
            JSONObject jsonObject = new JSONObject(resp);
            if (jsonObject.getInt("error") != 0) {
                Backend.jni_print("Non zero error: " + Integer.toString(jsonObject.getInt("error")) + "\n");
                throw new Exception("Error code");
            }

            return jsonObject;
        } catch (Exception e) {
            throw e;
        }
    }

    public static void init() {
        cInit(new Backend(), new Conn());
    }

    public static String logLocation = "main";

    // debug function for both Java frontend and JNI backend
    private static String basicLog = "";
    public static void jni_print(String arg) {
        basicLog += arg;
        if (logLocation == "main") {
            MainActivity.getInstance().setErrorText(basicLog);
        } else if (logLocation == "gallery") {
            gallery.getInstance().setErrorText(basicLog);
        }
    }
}
