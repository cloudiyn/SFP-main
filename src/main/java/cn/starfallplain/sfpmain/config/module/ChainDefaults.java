package cn.starfallplain.sfpmain.config.module;

import java.util.List;

/**
 * 连锁挖矿的内置默认方块表（{@code chain.yml} 的 {@code blocks} 留空时生效）。
 * <p>
 * 收录原则：<b>成脉状出现、且连锁挖掉有实际收益</b>的方块。
 * <ul>
 *   <li>收录：各种矿石与其下界变种（含深层）</li>
 *   <li>不收录：钻石 / 青金石 / 绿宝石 —— 它们是单块散布的宝石，
 *       不是矿脉，连锁既没收益也不该发生</li>
 *   <li>不收录：泥土 / 石头 / 砂砾 —— 那是地形方块，连锁会误伤路边与建筑</li>
 * </ul>
 * 用户在 {@code chain.yml} 里写了 {@code blocks} 就完全以配置为准，这个表不再参与判定。
 *
 * <p>⚠ 表里的每个名字都必须真实存在于 {@code org.bukkit.Material}。
 * 拼错（如把 {@code DEEPSLATE_REDSTONE_ORE} 写成 {@code DEEPSLATE_REDSLONE_ORE}）
 * 不会抛异常，只会让连锁对该矿静默失效 —— 所以 {@code /sfp test} 会逐个核对。
 */
public final class ChainDefaults {

    private ChainDefaults() {
    }

    /** 完整的默认收录表（2026-10-18 按 Paper 26.3 的 Material 枚举逐个核对过） */
    public static final List<String> DEFAULT_BLOCKS = List.of(
            "COAL_ORE", "DEEPSLATE_COAL_ORE",
            "COPPER_ORE", "DEEPSLATE_COPPER_ORE",
            "IRON_ORE", "DEEPSLATE_IRON_ORE",
            "GOLD_ORE", "DEEPSLATE_GOLD_ORE",
            "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE",
            "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE",
            "NETHER_GOLD_ORE",
            "NETHER_QUARTZ_ORE",
            "ANCIENT_DEBRIS"
    );
}