package com.fongmi.android.tv.player.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.ui.danmaku.Danmaku;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class JsonDanmakuParserTest {

    private final JsonDanmakuParser parser = new JsonDanmakuParser();

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String repeat(String value, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) builder.append(value);
        return builder.toString();
    }

    private static byte[] head(InputStream is, int length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64];
        int n;
        while (out.size() < length && (n = is.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    @Test
    public void sniffAndParsePlainArray() throws IOException {
        String json = "[{\"time\":2.5,\"text\":\"b\",\"mode\":4,\"color\":16711680,\"size\":25},"
                + "{\"time\":1.5,\"text\":\"a\",\"mode\":1,\"color\":16777215,\"size\":25}]";
        assertTrue(parser.sniff(stream(json), 512));
        List<Danmaku> items = parser.parse(stream(json));
        assertEquals(2, items.size());
        assertEquals(1500, items.get(0).timeMs);
        assertEquals(Danmaku.TYPE_SCROLL, items.get(0).type);
        assertEquals("a", items.get(0).text);
        assertEquals(2500, items.get(1).timeMs);
        assertEquals(Danmaku.TYPE_BOTTOM, items.get(1).type);
        assertEquals(0xFFFF0000, items.get(1).color);
    }

    @Test
    public void sniffAndParseWrappedObject() throws IOException {
        String json = "{\"code\":1,\"name\":\"第01集\",\"danmaku\":[{\"time\":0,\"text\":\"x\"}]}";
        assertTrue(parser.sniff(stream(json), 512));
        List<Danmaku> items = parser.parse(stream(json));
        assertEquals(1, items.size());
        assertEquals("x", items.get(0).text);
        assertEquals(Danmaku.TYPE_SCROLL, items.get(0).type);
    }

    @Test
    public void parseBiliStylePAttr() throws IOException {
        String json = "[{\"p\":\"12.5,4,25,16711680\",\"text\":\"y\"}]";
        assertTrue(parser.sniff(stream(json), 512));
        List<Danmaku> items = parser.parse(stream(json));
        assertEquals(1, items.size());
        assertEquals(12500, items.get(0).timeMs);
        assertEquals(Danmaku.TYPE_BOTTOM, items.get(0).type);
        assertEquals(0xFFFF0000, items.get(0).color);
    }

    @Test
    public void sniffDeclinesBiliXml() throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i><d p=\"1,1,25,0\">t</d></i>";
        assertFalse(parser.sniff(stream(xml), 512));
    }

    @Test
    public void sniffDeclinesPlatformJsonMarkers() throws IOException {
        assertFalse(parser.sniff(stream("{\"barrage_list\":[{\"text\":\"a\",\"time\":1}]}"), 512));
        assertFalse(parser.sniff(stream("{\"data\":{\"result\":[{\"playat\":1,\"content\":\"a\"}]}}"), 512));
    }

    @Test
    public void sniffDeclinesNonArrayWrapper() throws IOException {
        assertFalse(parser.sniff(stream("{\"data\":{\"items\":[{\"text\":\"a\",\"time\":1}]}}"), 512));
    }

    @Test
    public void sniffDeclinesArrayWithoutDanmakuKeys() throws IOException {
        assertFalse(parser.sniff(stream("[{\"name\":\"a\",\"url\":\"http://x\"}]"), 512));
    }

    @Test
    public void sniffToleratesUtf8Bom() throws IOException {
        String json = "[{\"time\":1,\"text\":\"a\"}]";
        InputStream withBom = new ByteArrayInputStream(
                concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(parser.sniff(withBom, 512));
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] all = new byte[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    @Test
    public void parseLargePayloadPastSniffWindow() throws IOException {
        String filler = repeat("{\"time\":10,\"text\":\"filler\"},", 200);
        String json = "[" + filler + "{\"time\":20,\"text\":\"tail\",\"mode\":5}]";
        assertTrue(parser.sniff(stream(json), 512));
        List<Danmaku> items = parser.parse(stream(json));
        assertEquals(201, items.size());
        assertEquals("tail", items.get(items.size() - 1).text);
        assertEquals(Danmaku.TYPE_TOP, items.get(items.size() - 1).type);
    }

    @Test
    public void headReaderIsNotDistortedByBom() throws IOException {
        String json = "[{\"time\":1,\"text\":\"a\"}]";
        InputStream is = new ByteArrayInputStream(
                concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, json.getBytes(StandardCharsets.UTF_8)));
        byte[] read = head(is, 512);
        assertEquals(json.getBytes(StandardCharsets.UTF_8).length + 3, read.length);
    }

    @Test
    public void modeWithoutValueDefaultsToScroll() throws IOException {
        String json = "[{\"time\":3.5,\"text\":\"z\"}]";
        List<Danmaku> items = parser.parse(stream(json));
        assertEquals(1, items.size());
        assertEquals(3500, items.get(0).timeMs);
        assertEquals(Danmaku.TYPE_SCROLL, items.get(0).type);
        assertEquals(0xFFFFFFFF, items.get(0).color);
    }
}
