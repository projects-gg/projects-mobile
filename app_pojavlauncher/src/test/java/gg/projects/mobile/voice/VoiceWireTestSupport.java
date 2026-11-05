package gg.projects.mobile.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

/** Reads the frames the machine sends, for the tests. */
final class VoiceWireTestSupport {
    private VoiceWireTestSupport() {}

    static JsonObject frame(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    static String type(String text) {
        return frame(text).get("t").getAsString();
    }

    /** The rid of the first frame that carries one. */
    static int rid(List<VoiceMachine.Effect> effects) {
        for (VoiceMachine.Effect e : effects) {
            if (e.kind != VoiceMachine.Effect.Kind.SEND) continue;
            JsonObject o = frame(e.frame);
            if (o.has("rid")) return o.get("rid").getAsInt();
        }
        throw new AssertionError("no frame with a rid");
    }
}
