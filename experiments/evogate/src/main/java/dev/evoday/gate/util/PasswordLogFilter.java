package dev.evoday.gate.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.List;
import java.util.Locale;

// чтобы пароли не светились в консоли. снять фильтр с log4j нельзя, поэтому просто выключаем флагом
public final class PasswordLogFilter extends AbstractFilter {

    private static final String MARK = "issued server command: /";
    private static final List<String> COMMANDS = List.of(
            "login", "l", "register", "reg", "changepassword", "changepass", "evogate changepass");

    private volatile boolean active;

    @Override
    public Filter.Result filter(LogEvent event) {
        if (!active || event == null || event.getMessage() == null) {
            return Filter.Result.NEUTRAL;
        }
        String text = event.getMessage().getFormattedMessage();
        int at = text == null ? -1 : text.indexOf(MARK);
        if (at < 0) {
            return Filter.Result.NEUTRAL;
        }
        String command = text.substring(at + MARK.length()).toLowerCase(Locale.ROOT);
        int colon = command.indexOf(':');
        int space = command.indexOf(' ');
        if (colon >= 0 && (space < 0 || colon < space)) {
            command = command.substring(colon + 1); // /evogate:login
        }
        for (String c : COMMANDS) {
            if (command.equals(c) || command.startsWith(c + " ")) {
                return Filter.Result.DENY;
            }
        }
        return Filter.Result.NEUTRAL;
    }

    public void install() {
        active = true;
        ((Logger) LogManager.getRootLogger()).addFilter(this);
    }

    public void uninstall() {
        active = false;
    }
}
