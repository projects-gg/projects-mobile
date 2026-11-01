package gg.projects.mobile.afk.api;

import java.util.Collections;
import java.util.List;

/** {@link PhoneAfk#add} or {@link PhoneAfk#update} refused a draft; {@link #problems} says why (never empty). */
public final class AfkDraftException extends Exception {
    public final List<AfkProblem> problems;

    public AfkDraftException(List<AfkProblem> problems) {
        super(String.valueOf(problems));
        this.problems = Collections.unmodifiableList(problems);
    }
}
