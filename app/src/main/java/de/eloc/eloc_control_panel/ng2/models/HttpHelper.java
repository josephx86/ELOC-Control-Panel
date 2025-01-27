package de.eloc.eloc_control_panel.ng2.models;

import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import java.util.ArrayList;

import de.eloc.eloc_control_panel.models.ElocDeviceInfo;
import de.eloc.eloc_control_panel.ng.interfaces.ElocDeviceInfoListCallback;
import de.eloc.eloc_control_panel.ng2.App;

public class HttpHelper {
    private final RequestQueue mRequestQueue;
    private static HttpHelper instance;

    public static HttpHelper getInstance() {
        if (instance == null) {
            instance = new HttpHelper();
        }
        return instance;
    }

    private HttpHelper() {
        mRequestQueue = Volley.newRequestQueue(App.Companion.getInstance());
    }

    public void getElocDevicesAsync(ElocDeviceInfoListCallback callback) {
        // Volley will do request on background thread... no need for an executor.
        // But that also means remember to use the callback!
        String address = "http://128.199.206.198/ELOC/map/appmap.php";
        StringRequest request = new StringRequest(address, response -> {
            String rangerName = PreferencesHelper.Companion.getInstance().getRangerName();
            ArrayList<ElocDeviceInfo> deviceInfos = ElocDeviceInfo.parseForRanger(response, rangerName);
            if (callback != null) {
                callback.handler(deviceInfos);
            }
        }, error -> {
            // Return empty list on error
            if (callback != null) {
                callback.handler(new ArrayList<>());
            }
        });

        mRequestQueue.add(request);
        mRequestQueue.start();
    }

}
