package com.lvfast.streaming;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class MediaStreamingApplication {

    private static final String OPS_ROLE_OPTION = "--app.ops.roles=";

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(MediaStreamingApplication.class, args);
        if (operatorCommandRequested(args)) {
            // The operator command (docs/runbooks/admin-bootstrap.md) is a bounded CLI. The RabbitMQ
            // listeners would otherwise keep a non-web application alive after the command ran, so
            // close the context and exit once the command has completed.
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    /** True when the documented operator command was requested on the command line. */
    static boolean operatorCommandRequested(String[] args) {
        return args != null && Arrays.stream(args)
                .anyMatch(argument -> argument != null && argument.startsWith(OPS_ROLE_OPTION));
    }
}
