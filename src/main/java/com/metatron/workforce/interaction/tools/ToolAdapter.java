package com.metatron.workforce.interaction.tools;

/** Provider-neutral capability adapter. Implementations may wrap GitHub, Cloudflare, Hetzner, etc. */
public interface ToolAdapter {
    String capability();
    ToolResult execute(ToolRequest request);
}
