package com.inventoryerp.qualitycontrol.application;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** The random source of {@code frequency = random} control points (a bean so a test can replace it). */
@Component
public class QcRandom {

    /** A uniform value in [0, 1). */
    public double next() {
        return ThreadLocalRandom.current().nextDouble();
    }
}
