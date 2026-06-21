package capture;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import db.TestCaseRepository;
import db.TestCaseRepository.SaveRequest;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Business logic for capturing requests as test cases.
 * All DB writes are dispatched to a single background thread to keep the EDT free.
 * Listeners are called back on the EDT after each successful save.
 */
public class CaptureService {

    private final MontoyaApi api;
    private final TestCaseRepository tcRepo;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bac-capture");
        t.setDaemon(true);
        return t;
    });

    private final List<Runnable> onSaveListeners = new ArrayList<>();

    public CaptureService(MontoyaApi api, TestCaseRepository tcRepo) {
        this.api = api;
        this.tcRepo = tcRepo;
    }

    public void addOnSaveListener(Runnable listener) {
        onSaveListeners.add(listener);
    }

    /** Quick-save a single request to Inbox with no metadata dialog. */
    public void quickSaveToInbox(HttpRequestResponse reqResp) {
        if (reqResp == null || reqResp.request() == null) {
            api.logging().logToOutput("[BAC] Quick-save skipped: no request available.");
            return;
        }
        if (!reqResp.hasResponse()) {
            api.logging().logToOutput("[BAC] Quick-save: request has no response yet "
                + "(captured with empty baseline — re-baseline later to fill it).");
        }
        SaveRequest req = buildSaveRequest(reqResp, null, null, null, null);
        submit(req);
    }

    /** Save with explicit metadata (called from SaveDialog). */
    public void saveWithMetadata(HttpRequestResponse reqResp,
                                 String name, Long folderId,
                                 Long ownerAccountId, String notes) {
        if (reqResp == null || reqResp.request() == null) {
            api.logging().logToOutput("[BAC] Save skipped: no request available.");
            return;
        }
        SaveRequest req = buildSaveRequest(reqResp, name, folderId, ownerAccountId, notes);
        submit(req);
    }

    // ---- internals -----------------------------------------------------

    private void submit(SaveRequest req) {
        executor.submit(() -> {
            try {
                long id = tcRepo.save(req);
                api.logging().logToOutput(
                    "[BAC] Saved: " + req.method() + " " + req.url() + " → id=" + id);
                notifyListeners();
            } catch (Exception e) {
                api.logging().logToError("[BAC] Save failed: " + e.getMessage());
            }
        });
    }

    private SaveRequest buildSaveRequest(HttpRequestResponse reqResp,
                                         String name, Long folderId,
                                         Long ownerAccountId, String notes) {
        var req = reqResp.request();
        var svc = req.httpService();
        boolean hasResp = reqResp.hasResponse() && reqResp.response() != null;

        byte[] requestRaw  = req.toByteArray().getBytes();
        byte[] responseRaw = hasResp ? reqResp.response().toByteArray().getBytes() : new byte[0];
        int status         = hasResp ? reqResp.response().statusCode() : 0;
        int length         = hasResp && reqResp.response().body() != null
            ? reqResp.response().body().length() : 0;

        return new SaveRequest(
            name,
            notes,
            folderId,
            ownerAccountId,
            req.method(),
            req.url(),
            svc.host(),
            svc.port(),
            svc.secure(),
            requestRaw,
            responseRaw,
            status,
            length
        );
    }

    private void notifyListeners() {
        for (Runnable l : onSaveListeners) {
            SwingUtilities.invokeLater(l);
        }
    }
}
