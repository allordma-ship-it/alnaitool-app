package app.alnai.notes;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.Ink;
import com.google.mlkit.vision.digitalink.RecognitionContext;
import com.google.mlkit.vision.digitalink.RecognitionResult;
import com.google.mlkit.vision.digitalink.WritingArea;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/**
 * On-device handwriting recognition (Google ML Kit Digital Ink).
 * The language model is downloaded once per language, then recognition works offline.
 */
@CapacitorPlugin(name = "InkRecognizer")
public class InkRecognizerPlugin extends Plugin {

    @PluginMethod
    public void recognize(PluginCall call) {
        String language = call.getString("language", "en");
        JSArray lines = call.getArray("lines");
        float width = call.getFloat("width", 0f);
        float height = call.getFloat("height", 0f);
        if (lines == null || lines.length() == 0) {
            call.reject("No strokes");
            return;
        }

        final List<Ink> inks = new ArrayList<>();
        final DigitalInkRecognitionModel model;
        try {
            for (int l = 0; l < lines.length(); l++) {
                JSONArray strokes = lines.getJSONArray(l);
                Ink.Builder ink = Ink.builder();
                for (int s = 0; s < strokes.length(); s++) {
                    JSONArray pts = strokes.getJSONArray(s);
                    Ink.Stroke.Builder stroke = Ink.Stroke.builder();
                    for (int i = 0; i + 1 < pts.length(); i += 2) {
                        stroke.addPoint(Ink.Point.create((float) pts.getDouble(i), (float) pts.getDouble(i + 1)));
                    }
                    ink.addStroke(stroke.build());
                }
                inks.add(ink.build());
            }
            DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(language);
            if (id == null) {
                call.reject("Language not supported: " + language);
                return;
            }
            model = DigitalInkRecognitionModel.builder(id).build();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
            return;
        }

        RemoteModelManager.getInstance()
            .download(model, new DownloadConditions.Builder().build())
            .addOnFailureListener((e) -> call.reject("Model download failed: " + e.getMessage(), e))
            .addOnSuccessListener((unused) -> {
                DigitalInkRecognizer recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build());
                RecognitionContext.Builder ctx = RecognitionContext.builder().setPreContext("");
                if (width > 0 && height > 0) ctx.setWritingArea(new WritingArea(width, height));
                RecognitionContext context = ctx.build();

                List<Task<RecognitionResult>> tasks = new ArrayList<>();
                for (Ink ink : inks) tasks.add(recognizer.recognize(ink, context));

                Tasks.whenAllComplete(tasks).addOnCompleteListener((done) -> {
                    JSArray out = new JSArray();
                    for (Task<RecognitionResult> t : tasks) {
                        String text = "";
                        if (t.isSuccessful() && t.getResult() != null && !t.getResult().getCandidates().isEmpty()) {
                            text = t.getResult().getCandidates().get(0).getText();
                        }
                        out.put(text);
                    }
                    recognizer.close();
                    JSObject res = new JSObject();
                    res.put("lines", out);
                    call.resolve(res);
                });
            });
    }
}
