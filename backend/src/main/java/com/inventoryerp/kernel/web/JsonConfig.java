package com.inventoryerp.kernel.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * JSON wire parity with Nest/Prisma (docs/CONTRACT.md section 8): Prisma Decimal serializes as a
 * plain string without trailing zeros ("12.5", not 12.500000); unknown request fields are rejected
 * (ValidationPipe forbidNonWhitelisted); dates are ISO-8601 strings (Jackson 3 default).
 */
@Configuration
public class JsonConfig {

    /** JavaScript {@code Date.toISOString()} format: always UTC, always exactly 3 fraction digits. */
    public static final DateTimeFormatter JS_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Bean
    JsonMapperBuilderCustomizer nestCompatibleJson() {
        SimpleModule wire = new SimpleModule("nest-wire-format");
        wire.addSerializer(BigDecimal.class, new PrismaDecimalSerializer());
        wire.addSerializer(Instant.class, new JsInstantSerializer());
        return builder -> builder.addModule(wire).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public static String toJsIso(Instant instant) {
        return JS_ISO.format(instant);
    }

    static final class JsInstantSerializer extends ValueSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializationContext ctx) {
            gen.writeString(JS_ISO.format(value));
        }
    }

    static final class PrismaDecimalSerializer extends ValueSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctx) {
            BigDecimal stripped = value.stripTrailingZeros();
            gen.writeString(stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString());
        }
    }
}
