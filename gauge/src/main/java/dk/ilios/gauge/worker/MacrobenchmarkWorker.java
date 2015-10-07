/*
 * Copyright (C) 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.ilios.gauge.worker;

import static dk.ilios.gauge.util.Reflection.getAnnotatedMethods;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableSet;

import java.lang.reflect.Method;
import java.util.Map;

import dk.ilios.gauge.AfterRep;
import dk.ilios.gauge.BeforeRep;
import dk.ilios.gauge.internal.benchmark.BenchmarkClass;
import dk.ilios.gauge.model.Measurement;
import dk.ilios.gauge.model.Value;
import dk.ilios.gauge.util.Util;

/**
 * The {@link Worker} implementation for macro benchmarks, i.e benchmarks which runtime are measured in milliseconds,
 * not nanoseconds.
 */
public class MacrobenchmarkWorker extends Worker {
    private final Stopwatch stopwatch;
    private final ImmutableSet<Method> beforeRepMethods;
    private final ImmutableSet<Method> afterRepMethods;
    private final boolean gcBeforeEach;

    public MacrobenchmarkWorker(BenchmarkClass benchmarkClass, Ticker ticker, Map<String, String> workerOptions) {
        super(benchmarkClass.getInstance(), benchmarkClass.getMethod());
        this.stopwatch = Stopwatch.createUnstarted(ticker);
        this.beforeRepMethods = getAnnotatedMethods(benchmark.getClass(), BeforeRep.class);
        this.afterRepMethods = getAnnotatedMethods(benchmark.getClass(), AfterRep.class);
        this.gcBeforeEach = Boolean.parseBoolean(workerOptions.get("gcBeforeEach"));
    }

    @Override
    public void preMeasure(boolean inWarmup) throws Exception {
        for (Method beforeRepMethod : beforeRepMethods) {
            beforeRepMethod.invoke(benchmark);
        }
        if (gcBeforeEach && !inWarmup) {
            Util.forceGc();
        }
    }

    @Override
    public Iterable<Measurement> measure() throws Exception {
        stopwatch.start();
        benchmarkMethod.invoke(benchmark);
        long nanos = stopwatch.stop().elapsed(NANOSECONDS);
        stopwatch.reset();
        return ImmutableSet.of(new Measurement.Builder()
                .description("runtime")
                .weight(1)
                .value(Value.create(nanos, "ns"))
                .build());
    }

    @Override
    public void postMeasure() throws Exception {
        for (Method afterRepMethod : afterRepMethods) {
            afterRepMethod.invoke(benchmark);
        }
    }
}
