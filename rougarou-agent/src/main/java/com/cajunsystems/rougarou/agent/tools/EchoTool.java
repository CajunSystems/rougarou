package com.cajunsystems.rougarou.agent.tools;

/** Trivial tool that returns its input verbatim — useful for end-to-end tests. */
public final class EchoTool implements Tool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "text": {"type": "string"}
              },
              "required": ["text"]
            }
            """;

    @Override
    public String name() { return "echo"; }

    @Override
    public String description() {
        return "Echo back the supplied text. Used for harness smoke tests.";
    }

    @Override
    public String inputSchemaJson() { return SCHEMA; }

    @Override
    public String invoke(String argsJson) {
        return "{\"echo\":" + extractText(argsJson) + "}";
    }

    private static String extractText(String argsJson) {
        int idx = argsJson.indexOf("\"text\"");
        if (idx < 0) return "\"\"";
        int colon = argsJson.indexOf(':', idx);
        if (colon < 0) return "\"\"";
        int q1 = argsJson.indexOf('"', colon + 1);
        if (q1 < 0) return "\"\"";
        int q2 = argsJson.indexOf('"', q1 + 1);
        if (q2 < 0) return "\"\"";
        return argsJson.substring(q1, q2 + 1);
    }
}
