package dev.vlad.core.modules.auth;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.List;
import java.util.Locale;

/**
 * Не даёт серверу записать в лог строку "issued server command: /login пароль".
 * Фильтр висит на корневом логгере log4j. Убрать фильтр из log4j нельзя,
 * поэтому при выключении модуля он просто становится неактивным.
 */
final class PasswordLogFilter extends AbstractFilter {

    private static final String MARK = "issued server command: /";

    private final List<String> commands;
    private volatile boolean active;

    PasswordLogFilter(List<String> commands) {
        this.commands = commands;
    }

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
        for (String c : commands) {
            if (command.equals(c) || command.startsWith(c + " ")) {
                return Filter.Result.DENY;
            }
        }
        return Filter.Result.NEUTRAL;
    }

    void install() {
        active = true;
        ((Logger) LogManager.getRootLogger()).addFilter(this);
    }

    void uninstall() {
        active = false;
    }
}
