/*
ScanToUrl, a small console java app, that connects Datalogic scanner to the internet.
Copyright (C) 2018  Boris Fritscher

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ch.hearc.ig;

import com.dls.jpos.interpretation.*;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import jpos.*;
import jpos.events.*;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.cloud.firestore.*;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

// export GOOGLE_APPLICATION_CREDENTIALS=/path/to/my/key.json

/**
 * Script to scan QR code and send code to url
 */
public class ScanToUrl implements StatusUpdateListener, DataListener, ErrorListener {

    private static final String PROJECT_ID = "firebase-ptw";
    private static final String APP_ROOT = "business-card-app";

    private static final Logger logger = LogManager.getLogger("ScanHistory");

    static Scanner scanner = null;
    static ScanToUrl scannerListeners = null;
    byte[] scanData = new byte[]{};
    String scanDataLabel;
    int itemDataCount = 0;
    int scanDataType = -1;

    private static CloseableHttpAsyncClient httpclient;
    private static Pattern qrURLExtractPattern;
    private static String targetUrl;
    private static Firestore db;

    private static String sep = System.getProperty("line.separator");

    public static void main(String[] args) {

        String logicalName = "DLS-GBT4400-USB-OEM";
        String qrURLRegex = "economie\\.digital.*a=(\\d+)";
        qrURLExtractPattern = Pattern.compile(qrURLRegex);

        targetUrl = "http://dbl.requestcatcher.com/?=";

        scannerListeners = new ScanToUrl();

        // start scanning
        try {
            FirestoreOptions firestoreOptions = FirestoreOptions.getDefaultInstance().toBuilder()
                    .setProjectId(PROJECT_ID)
                    .build();
            db = firestoreOptions.getService();
            DocumentReference docRef = db.collection(APP_ROOT).document("config");
            // block retrieve the document
            DocumentSnapshot document = docRef.get().get();
            if (document.exists()) {
                System.out.println("Loading remote config");
                logicalName = (String) document.getData().getOrDefault("scannerName", logicalName);
                qrURLExtractPattern = Pattern.compile((String) document.getData().getOrDefault("qrURLExtractPattern", qrURLRegex));
                targetUrl = (String) document.getData().getOrDefault("targetUrl", targetUrl);
            } else {
                System.out.println("No remote config found!");
            }
            System.out.println("scannerName: " + logicalName);
            System.out.println("qrURLExtractPattern: " + qrURLExtractPattern.pattern());
            System.out.println("targetUrl: " + targetUrl);

            //Instantiate a scanner object
            scanner = new Scanner();

            //Add listeners for errors, data events, and status update events
            scanner.addErrorListener(scannerListeners);
            scanner.addDataListener(scannerListeners);
            scanner.addStatusUpdateListener(scannerListeners);

            //Open the scanner specifying which scanner you are using from jpos.xml
            scanner.open(logicalName);
            System.out.println("Open");
            //Claim the Scanner (depending on interface it could be usable)
            scanner.claim(1000);
            scanner.setDeviceEnabled(true);
            System.out.println("enabled");
            httpclient = HttpAsyncClients.createDefault();
            httpclient.start();

            scanner.setDataEventEnabled(true);

            //Wait until enter key is pressed to exit
            System.in.read();
            httpclient.close();
            scanner.setDeviceEnabled(false);
            System.out.println("Disabled");
            scanner.release();
            System.out.println("Released");
            scanner.close();
            System.out.println("Closed");

        } catch (JposException je) {
            System.out.println("JPOS Exception: " + je.getMessage() + "\\n" + je.getStackTrace());
        } catch (Exception e) {
            System.out.println("Exception: " + e.getMessage() + "\\n" + e.getStackTrace());
        }
        System.exit(0);
        return;
    }


    @Override
    public void dataOccurred(DataEvent de) {
        doDataUpdate();
    }

    @Override
    public void errorOccurred(ErrorEvent ee) {
        try {
            // Post error event data to dialog doDataUpdate();
            int errCode = ee.getErrorCode();
            int errCodeEx = ee.getErrorCodeExtended();
            int errCodeRes = ee.getErrorResponse();

            System.out.println("Error event occured: " + convertErrorCodeToString(errCode) + " : " + convertErrorCodeToString(errCodeEx) + " : " + convertErrorCodeToString(errCodeRes) + sep);

            scanner.setDataEventEnabled(true);
        } catch (JposException ex) {
            System.err.println("JposException: " + ex.getLocalizedMessage());
        }
    }

    @Override
    public void statusUpdateOccurred(StatusUpdateEvent sue) {
        int status = sue.getStatus();
        switch (status) {
            case JposConst.JPOS_PS_OFF:
                System.out.println("Power Off");
                break;
            case JposConst.JPOS_PS_OFFLINE:
                System.out.println("Power Offline");
                break;
            case JposConst.JPOS_PS_OFF_OFFLINE:
                System.out.println("Power Off and Offline");
                break;
            case JposConst.JPOS_PS_ONLINE:
                System.out.println("Power Online");
                break;
            case jpos.JposConst.JPOS_PS_UNKNOWN:
                System.out.println("Power Unknown");
                break;
            default:
                System.out.println("GOT status Code: (" + status + ") " + convertErrorCodeToString(status));
        }
    }

    public void doDataUpdate() {
        try {
            scanData = scanner.getScanData();
            scanDataLabel = trimUnprintable(scanner.getScanDataLabel());
            scanDataType = scanner.getScanDataType();
            if (scanData.length > 0) {
                System.out.println("Item Count: " + Integer.toString(++itemDataCount));
            }
            logger.info(scanDataLabel);
            // this setting of the DataEventEnable is because the tester
            // doesn't want to have to continually check the box after
            // every single scan
            scanner.setDataEventEnabled(true);
            System.out.println("Scan Data: " + trimUnprintable(scanData));
            System.out.println("Scan Data Label: " + scanDataLabel);
            System.out.println("Scan Data Type: " + scanDataType);
            System.out.println("Data Count: " + Integer.toString(scanner.getDataCount()));
            System.out.println(sep + "********************************************************************" + sep);
            Matcher matchingGroups = qrURLExtractPattern.matcher(scanDataLabel);
            if (matchingGroups.find()) {
                String registrationId = matchingGroups.group(1);
                System.out.println("FOUND id to register: " + registrationId);
                this.notifyTargetWebhook(targetUrl + registrationId);
                this.updateFirestoreRegistration(registrationId);
            }

        } catch (Exception je) {
            System.out.println("Exception in doDataUpdate(): " + je.getMessage());
        }
    }

    private void updateFirestoreRegistration(String registrationId) {
        CollectionReference cards = db.collection(APP_ROOT + "/data/cards");
        Query query = cards.whereEqualTo("odoo.registration.id", Integer.parseInt(registrationId));
        // async to continue scanning
        ApiFutures.addCallback(query.get(), new ApiFutureCallback<QuerySnapshot>() {
            @Override
            public void onFailure(Throwable t) {
                t.printStackTrace();
            }

            @Override
            public void onSuccess(QuerySnapshot result) {
                for (DocumentSnapshot document : result.getDocuments()) {
                    System.out.println("FOUND registration in firestore");
                    if (!document.getString("odoo.registration.state").equals("done")) {
                        System.out.println("UPDATE required");
                        Map<String, Object> update = new HashMap<>();
                        update.put("odoo.registration.state", "done");
                        document.getReference().update(update);
                        Map<String, Object> data = new HashMap<>();
                        data.put("card", document.getReference());
                        db.collection(APP_ROOT + "/data/welcome_queue").add(data);
                        System.out.println("UPDATE done");
                    }
                }
            }
        });
    }

    private void notifyTargetWebhook(String url) {
        try {
            HttpGet request = new HttpGet(url);
            httpclient.execute(request, new FutureCallback<HttpResponse>() {

                public void completed(final HttpResponse response) {
                    System.out.println(request.getRequestLine() + "->" + response.getStatusLine());
                }

                public void failed(final Exception ex) {
                    System.out.println(request.getRequestLine() + "->" + ex);
                }

                public void cancelled() {
                    System.out.println(request.getRequestLine() + " cancelled");
                }
            });
        } catch (Exception ex) {
            ex.printStackTrace();
            System.err.println("Exception: " + ex.getMessage());
        }
    }

    private String convertErrorCodeToString(int errorCode) {
        String codeString = "";
        switch (errorCode) {
            case DeviceErrorStatusListener.ERR_CMD:
                codeString = "ERR_CMD";
                break;
            case DeviceErrorStatusListener.ERR_NO_WEIGHT:
                codeString = "ERR_NO_WEIGHT";
                break;
            case DeviceErrorStatusListener.ERR_DATA:
                codeString = "ERR_DATA";
                break;
            case DeviceErrorStatusListener.ERR_READ:
                codeString = "ERR_READ";
                break;
            case DeviceErrorStatusListener.ERR_NO_DISPLAY:
                codeString = "ERR_NO_DISPLAY";
                break;
            case DeviceErrorStatusListener.ERR_HARDWARE:
                codeString = "ERR_HARDWARE";
                break;
            case DeviceErrorStatusListener.ERR_CMD_REJECT:
                codeString = "ERR_CMD_REJECT";
                break;
            case DeviceErrorStatusListener.ERR_CAPACITY:
                codeString = "ERR_CAPACITY";
                break;
            case DeviceErrorStatusListener.ERR_REQUIRES_ZEROING:
                codeString = "ERR_REQUIRES_ZEROING";
                break;
            case DeviceErrorStatusListener.ERR_WARMUP:
                codeString = "ERR_WARMUP";
                break;
            case DeviceErrorStatusListener.ERR_DUPLICATE:
                codeString = "ERR_DUPLICATE";
                break;
            case DeviceErrorStatusListener.ERR_FLASHING:
                codeString = "ERR_FLASHING";
                break;
            case DeviceErrorStatusListener.ERR_BUSY:
                codeString = "ERR_BUSY";
                break;
            case DeviceErrorStatusListener.ERR_CHECKDIGIT:
                codeString = "ERR_CHECKDIGIT";
                break;
            case DeviceErrorStatusListener.ERR_DIO_NOT_ALLOWED:
                codeString = "ERR_DIO_NOT_ALLOWED";
                break;
            case DeviceErrorStatusListener.ERR_DIO_UNDEFINED:
                codeString = "ERR_DIO_UNDEFINED";
                break;
            case DeviceErrorStatusListener.ERR_DEVICE_REMOVED:
                codeString = "ERR_DEVICE_REMOVED";
                break;
            case DeviceErrorStatusListener.ERR_SCALE_AT_ZERO:
                codeString = "ERR_SCALE_AT_ZERO";
                break;
            case DeviceErrorStatusListener.ERR_SCALE_UNDER_ZERO:
                codeString = "ERR_SCALE_UNDER_ZERO";
                break;
            case DeviceErrorStatusListener.ERR_DEVICE_REATTACHED:
                codeString = "ERR_DEVICE_REATTACHED";
                break;
            case DeviceErrorStatusListener.STATUS_ALIVE:
                codeString = "STATUS_ALIVE";
                break;
            case DeviceErrorStatusListener.STATUS_NOT_ALIVE:
                codeString = "STATUS_NOT_ALIVE";
                break;
            case DeviceErrorStatusListener.STATUS_ENABLED:
                codeString = "STATUS_ENABLED";
                break;
            case DeviceErrorStatusListener.STATUS_NOT_ENABLED:
                codeString = "STATUS_NOT_ENABLED";
                break;
            case JposConst.JPOS_S_CLOSED:
                codeString = "JPOS_S_CLOSED";
                break;
            case JposConst.JPOS_S_IDLE:
                codeString = "JPOS_S_IDLE";
                break;
            case JposConst.JPOS_S_BUSY:
                codeString = "JPOS_S_BUSY";
                break;
            case JposConst.JPOS_S_ERROR:
                codeString = "JPOS_S_ERROR";
                break;
            case JposConst.JPOSERR:
                codeString = "JPOSERR";
                break;
            case JposConst.JPOSERREXT:
                codeString = "JPOSERREXT";
                break;
            case JposConst.JPOS_SUCCESS:
                codeString = "JPOS_SUCCESS";
                break;
            case JposConst.JPOS_E_CLOSED:
                codeString = "JPOS_E_CLOSED";
                break;
            case JposConst.JPOS_E_CLAIMED:
                codeString = "JPOS_E_CLAIMED";
                break;
            case JposConst.JPOS_E_NOTCLAIMED:
                codeString = "JPOS_E_NOTCLAIMED";
                break;
            case JposConst.JPOS_E_NOSERVICE:
                codeString = "JPOS_E_NOSERVICE";
                break;
            case JposConst.JPOS_E_DISABLED:
                codeString = "JPOS_E_DISABLED";
                break;
            case JposConst.JPOS_E_ILLEGAL:
                codeString = "JPOS_E_ILLEGAL";
                break;
            case JposConst.JPOS_E_NOHARDWARE:
                codeString = "JPOS_E_NOHARDWARE";
                break;
            case JposConst.JPOS_E_OFFLINE:
                codeString = "JPOS_E_OFFLINE";
                break;
            case JposConst.JPOS_E_NOEXIST:
                codeString = "JPOS_E_NOEXIST";
                break;
            case JposConst.JPOS_E_EXISTS:
                codeString = "JPOS_E_EXISTS";
                break;
            case JposConst.JPOS_E_FAILURE:
                codeString = "JPOS_E_FAILURE";
                break;
            case JposConst.JPOS_E_TIMEOUT:
                codeString = "JPOS_E_TIMEOUT";
                break;
            case JposConst.JPOS_E_BUSY:
                codeString = "JPOS_E_BUSY";
                break;
            case JposConst.JPOS_E_EXTENDED:
                codeString = "JPOS_E_EXTENDED";
                break;
            case JposConst.JPOS_E_DEPRECATED:
                codeString = "JPOS_E_DEPRECATED";
                break;
            case JposConst.JPOS_ESTATS_ERROR:
                codeString = "JPOS_ESTATS_ERROR";
                break;
            case JposConst.JPOS_EFIRMWARE_BAD_FILE:
                codeString = "JPOS_EFIRMWARE_BAD_FILE";
                break;
            case JposConst.JPOS_ESTATS_DEPENDENCY:
                codeString = "JPOS_ESTATS_DEPENDENCY";
                break;
            case JposConst.JPOS_PS_UNKNOWN:
                codeString = "JPOS_PS_UNKNOWN";
                break;
            case JposConst.JPOS_PS_ONLINE:
                codeString = "JPOS_PS_ONLINE";
                break;
            case JposConst.JPOS_PS_OFF:
                codeString = "JPOS_PS_OFF";
                break;
            case JposConst.JPOS_PS_OFFLINE:
                codeString = "JPOS_PS_OFFLINE";
                break;
            case JposConst.JPOS_PS_OFF_OFFLINE:
                codeString = "JPOS_PS_OFF_OFFLINE";
                break;
            case JposConst.JPOS_ER_RETRY:
                codeString = "JPOS_ER_RETRY";
                break;
            case JposConst.JPOS_ER_CLEAR:
                codeString = "JPOS_ER_CLEAR";
                break;
            case JposConst.JPOS_ER_CONTINUEINPUT:
                codeString = "JPOS_ER_CONTINUEINPUT";
                break;
            case JposConst.JPOS_SUE_UF_PROGRESS:
                codeString = "JPOS_SUE_UF_PROGRESS";
                break;
            case JposConst.JPOS_SUE_UF_COMPLETE:
                codeString = "JPOS_SUE_UF_COMPLETE";
                break;
            case JposConst.JPOS_SUE_UF_FAILED_DEV_OK:
                codeString = "JPOS_SUE_UF_FAILED_DEV_OK";
                break;
            case JposConst.JPOS_SUE_UF_FAILED_DEV_UNRECOVERABLE:
                codeString = "JPOS_SUE_UF_FAILED_DEV_UNRECOVERABLE";
                break;
            case JposConst.JPOS_SUE_UF_FAILED_DEV_NEEDS_FIRMWARE:
                codeString = "JPOS_SUE_UF_FAILED_DEV_NEEDS_FIRMWARE";
                break;
            case JposConst.JPOS_SUE_UF_FAILED_DEV_UNKNOWN:
                codeString = "JPOS_SUE_UF_FAILED_DEV_UNKNOWN";
                break;
            case JposConst.JPOS_SUE_UF_COMPLETE_DEV_NOT_RESTORED:
                codeString = "JPOS_SUE_UF_COMPLETE_DEV_NOT_RESTORED";
                break;
            case JposConst.JPOS_FOREVER:
                codeString = "JPOS_FOREVER";
                break;
        }
        return codeString;
    }

    /**
     * Converts a byte array into a String stripping any unprintable characters.
     *
     * @param aR - The byte array to convert.
     * @return
     */
    private String trimUnprintable(byte[] aR) {
        int i = 0;
        byte ch;
        int bPad = 0;

        if (aR == null || aR.length <= 0) {
            return null;
        }

        StringBuffer out = new StringBuffer(aR.length);

        while (i < aR.length) {
            ch = aR[i];

            if ((ch > 31) && (ch < 127)) {
                out.append((char) ch);
                bPad = 1;
            } else if ((ch >= 0) && (ch <= 9)) {
                out.append((char) (ch + 48));
            } else if (bPad == 1) {
                out.append(" ");
                bPad = 0;
            }

            i++;
        }
        return new String(out);
    }
}