package dev.pelopower.bridge;

public interface PowerSource {
    interface Listener {
        void onSample(PowerSample sample);
        void onStatus(String status);
    }

    void start(Listener listener);
    void stop();
    String name();
}

