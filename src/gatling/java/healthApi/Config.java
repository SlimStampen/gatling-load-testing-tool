package healthApi;

import uttil.Environment;

public class Config {
    public static final Environment CURRENT_ENV = Environment.DEV;

    public static String getBaseUrl() {
        return switch (CURRENT_ENV) {
            case DEV -> "http://localhost:8080/api";
            case TEST -> "https://health-api.test.memorylab.app/api";
            case STAGING -> "https://health-api.staging.memorylab.app/api";
            default -> throw new IllegalStateException("Unknown environment");
        };
    }
}
