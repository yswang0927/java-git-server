package com.gdk.git;

/**
 * Wrapper class for java.security.SecureRandom, which will periodically reseed
 * the PRNG in case an instance of the class has been running for a long time.
 *
 * @author Florian Zschocke
 */
public class SecureRandom {
    /**
     * Period (in ms) after which a new SecureRandom will be created in order to get a fresh random seed.
     */
    private static final long RESEED_PERIOD = 24 * 60 * 60 * 1000; /* 24 hours */

    private long last;
    private java.security.SecureRandom random;

    public SecureRandom() {
        // Make sure the SecureRandom is seeded right from the start.
        // This also lets any blocks during seeding occur at creation
        // and prevents it from happening when getting next random bytes.
        seed();
    }

    public byte[] randomBytes(int num) {
        byte[] bytes = new byte[num];
        nextBytes(bytes);
        return bytes;
    }

    public void nextBytes(byte[] bytes) {
        random.nextBytes(bytes);
        reseed(false);
    }

    void reseed(boolean forced) {
        long ts = System.currentTimeMillis();
        if (forced || (ts - last) > RESEED_PERIOD) {
            last = ts;
            runReseed();
        }
    }

    private void seed() {
        random = new java.security.SecureRandom();
        random.nextBytes(new byte[0]);
        last = System.currentTimeMillis();
    }

    private void runReseed() {
        // Have some other thread hit the penalty potentially incurred by reseeding,
        // so that we can immediately return and not block the operation in progress.
        new Thread() {
            public void run() {
                seed();
            }
        }.start();
    }

}
