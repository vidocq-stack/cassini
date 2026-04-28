package io.vidocq.cassini.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormDecoderTest {

    @Test
    void parsesBasicPairs() {
        Map<String, List<String>> m = FormDecoder.parse("a=1&b=2");
        assertEquals(List.of("1"), m.get("a"));
        assertEquals(List.of("2"), m.get("b"));
    }

    @Test
    void mergesRepeatedKeys() {
        Map<String, List<String>> m = FormDecoder.parse("tag=java&tag=rest&tag=cdi");
        assertEquals(List.of("java", "rest", "cdi"), m.get("tag"));
    }

    @Test
    void decodesPercentAndPlus() {
        Map<String, List<String>> m = FormDecoder.parse("q=hello+world&accent=%C3%A9");
        assertEquals(List.of("hello world"), m.get("q"));
        assertEquals(List.of("é"), m.get("accent"));
    }

    @Test
    void handlesEmpty() {
        assertEquals(Map.of(), FormDecoder.parse(""));
        assertEquals(Map.of(), FormDecoder.parse(null));
    }

    @Test
    void handlesFlagParam() {
        Map<String, List<String>> m = FormDecoder.parse("flag&k=v");
        assertEquals(List.of(""), m.get("flag"));
        assertEquals(List.of("v"), m.get("k"));
    }
}
