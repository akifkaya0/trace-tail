package sample.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.tracing.Step;
import sample.tracing.Work;

/** A stand-in for the mail server, which writes no lines here. Each mail is a MAIL_OUT step. */
final class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    void send(String to) {
        Step.start(log, "MAIL_OUT", "op", "send", "to", to).run(step -> Work.take(20, 80));
    }
}
