package wtf.nanoka.airplay.player.gstreamer.ui;

import java.nio.file.Path;

public interface SettingsController {

    Path settingsFile();

    Result save(ReceiverSettings settings);

    Result restart();

    record Result(boolean success, String message) {
        public static Result success(String message) {
            return new Result(true, message);
        }

        public static Result failure(String message) {
            return new Result(false, message);
        }
    }
}
