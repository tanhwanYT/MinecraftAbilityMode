package my.pkg;

import org.bukkit.Material;

public record RewardInfo(
        String id,
        String name,
        String summary,
        Material icon
) {}