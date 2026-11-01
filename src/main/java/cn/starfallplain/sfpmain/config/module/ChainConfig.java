package cn.starfallplain.sfpmain.config.module;

import cn.starfallplain.sfpmain.config.AbstractConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;

/**
 * 连锁挖矿配置（chain.yml）。
 */
public final class ChainConfig extends AbstractConfig {

    private boolean enabled;

    private boolean requireSneak;
    private boolean requireTool;
    private boolean survivalOnly;
    private boolean chainBots;

    private int maxBlocks;

    private double exhaustionPerBlock;
    private boolean stopWhenHungry;

    private Set<String> blocks;
    private boolean giveExperience;

    private boolean soundEnabled;
    private boolean soundPerBlock;
    private String soundName;
    private float soundVolume;
    private float soundPitch;

    private List<String> worldWhitelist;
    private List<String> worldBlacklist;

    private boolean messageEnabled;

    public ChainConfig(JavaPlugin plugin) {
        super(plugin, "chain.yml");
    }

    @Override
    protected void onLoaded() {
        enabled = getBoolean("enabled", true);

        requireSneak = getBoolean("require.sneak", true);
        requireTool = getBoolean("require.require-tool", true);
        survivalOnly = getBoolean("require.survival-only", true);
        chainBots = getBoolean("require.chain-bots", false);

        maxBlocks = Math.max(1, getInt("max-blocks", 32));

        exhaustionPerBlock = Math.max(0.0, getDouble("food.exhaustion-per-block", 0.1));
        stopWhenHungry = getBoolean("food.stop-when-hungry", true);

        List<String> configured = getStringList("blocks");
        // 集合字段必须在 onLoaded 内 new：子类字段初始化器在 super() 返回后才跑，
        // 那时这里赋的值会被覆盖掉（见 features.md 的配置约定）
        blocks = configured.isEmpty()
                ? Set.copyOf(ChainDefaults.DEFAULT_BLOCKS)
                : Set.copyOf(normalizeAll(configured));
        giveExperience = getBoolean("give-experience", true);

        soundEnabled = getBoolean("sound.enabled", true);
        soundPerBlock = getBoolean("sound.per-block", false);
        soundName = getString("sound.name", "BLOCK_STONE_BREAK");
        soundVolume = (float) getDouble("sound.volume", 0.6);
        soundPitch = (float) getDouble("sound.pitch", 1.2);

        worldWhitelist = List.copyOf(getStringList("worlds.whitelist"));
        worldBlacklist = List.copyOf(getStringList("worlds.blacklist"));

        messageEnabled = getBoolean("message.enabled", true);
    }

    /** 把配置里写的方块名统一成大写，和默认表保持同一种写法 */
    private static List<String> normalizeAll(List<String> names) {
        List<String> result = new java.util.ArrayList<>(names.size());
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                result.add(name.trim().toUpperCase(java.util.Locale.ROOT));
            }
        }
        return result;
    }

    public boolean isEnabled() { return enabled; }

    public boolean isRequireSneak() { return requireSneak; }

    public boolean isRequireTool() { return requireTool; }

    public boolean isSurvivalOnly() { return survivalOnly; }

    public boolean isChainBots() { return chainBots; }

    public int getMaxBlocks() { return maxBlocks; }

    public double getExhaustionPerBlock() { return exhaustionPerBlock; }

    public boolean isStopWhenHungry() { return stopWhenHungry; }

    public Set<String> getBlocks() { return blocks; }

    public boolean isGiveExperience() { return giveExperience; }

    public boolean isSoundEnabled() { return soundEnabled; }

    public boolean isSoundPerBlock() { return soundPerBlock; }

    public String getSoundName() { return soundName; }

    public float getSoundVolume() { return soundVolume; }

    public float getSoundPitch() { return soundPitch; }

    public List<String> getWorldWhitelist() { return worldWhitelist; }

    public List<String> getWorldBlacklist() { return worldBlacklist; }

    public boolean isMessageEnabled() { return messageEnabled; }

    /** 世界黑名单优先于白名单 */
    public boolean appliesToWorld(String worldName) {
        if (worldName == null) return false;
        String lower = worldName.toLowerCase(java.util.Locale.ROOT);
        for (String denied : worldBlacklist) {
            if (denied.equalsIgnoreCase(lower)) return false;
        }
        if (!worldWhitelist.isEmpty()) {
            for (String allowed : worldWhitelist) {
                if (allowed.equalsIgnoreCase(lower)) return true;
            }
            return false;
        }
        return true;
    }
}