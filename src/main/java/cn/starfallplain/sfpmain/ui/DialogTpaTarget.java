package cn.starfallplain.sfpmain.ui;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.menu.MenuManager;
import cn.starfallplain.sfpmain.teleport.TpaManager;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dialogUI 版的「选择传送目标」界面（在线玩家列表，不含自己）。
 * <p>
 * 一页 {@value #PAGE_SIZE} 个玩家，底部「上一页 / 返回主菜单 / 下一页」翻页。
 * 点击玩家 = 弹出「传送方向」选择框（传送到他身边 / 让他传送到你身边），
 * 与箱子 UI 的「左键 tpa / 右键 tpahere」功能对等（dialogUI 按钮无左右键，故用两步交互）。
 * <p>
 * 全部文案来自 {@code messages.yml}（{@code dialogui.*} / 复用 {@code tpa.gui-title}、
 * {@code teleport.prev|next}、{@code common.back-to-menu}），代码里不写死中文。
 */
public final class DialogTpaTarget {

    private static final int PAGE_SIZE = 6;
    private static final Duration LIFETIME = Duration.ofMinutes(10);

    private DialogTpaTarget() {
    }

    public static void open(SfpMain plugin, Player player, int page) {
        TpaManager tpaManager = plugin.getTpaManager();
        if (tpaManager == null) {
            player.sendMessage(plugin.getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            return;
        }

        List<Player> targets = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(player.getUniqueId())) {
                targets.add(p);
            }
        }

        if (targets.isEmpty()) {
            player.sendMessage(plugin.getMessage("tpa.no-targets", "<yellow>当前没有其他在线玩家。</yellow>"));
            return;
        }

        int totalPages = Math.max(1, (targets.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int currentPage = Math.max(0, Math.min(page, totalPages - 1));

        int start = currentPage * PAGE_SIZE;
        List<Player> pageTargets = targets.subList(start, Math.min(start + PAGE_SIZE, targets.size()));

        ClickCallback.Options options = ClickCallback.Options.builder().uses(1).lifetime(LIFETIME).build();
        List<ActionButton> buttons = new ArrayList<>();

        for (Player target : pageTargets) {
            final Player chosen = target;
            Component label = msg(plugin, "dialogui.entry-name", "<white>{name}</white>",
                    Map.of("name", target.getName()));
            Component tooltip = msg(plugin, "dialogui.tpa-entry-hover", "<gray>{world}</gray>",
                    Map.of("world", target.getWorld() != null ? target.getWorld().getName() : "?"));

            buttons.add(ActionButton.builder(label)
                    .tooltip(tooltip)
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player p && p.isOnline()) {
                            openDirectionDialog(plugin, p, chosen, tpaManager, options);
                        }
                    }, options))
                    .build());
        }

        if (currentPage > 0) {
            buttons.add(navButton(plugin, "teleport.prev", "<!i><yellow>上一页</yellow>",
                    player, currentPage - 1, options));
        }
        buttons.add(ActionButton.builder(plugin.getMessage("common.back-to-menu",
                        "<!i><yellow>返回主菜单</yellow>"))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p && p.isOnline()) {
                        MenuManager.openMenuByPreference(plugin, p);
                    }
                }, options))
                .build());
        if (currentPage < totalPages - 1) {
            buttons.add(navButton(plugin, "teleport.next", "<!i><yellow>下一页</yellow>",
                    player, currentPage + 1, options));
        }

        // 标题复用箱子 UI 的 tpa.gui-title
        Map<String, String> titlePh = new LinkedHashMap<>();
        titlePh.put("page", String.valueOf(currentPage + 1));
        titlePh.put("pages", String.valueOf(totalPages));
        titlePh.put("total", String.valueOf(targets.size()));
        Component title = msg(plugin, "tpa.gui-title",
                "<!i><aqua>选择传送目标</aqua> <gray>- 第 {page}/{pages} 页</gray>", titlePh);

        Dialog dialog = Dialog.create(builder -> builder
                .empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(1).build()));

        player.showDialog(dialog);
    }

    /**
     * 选定玩家后弹出「传送方向」选择框：传送到他身边（/tpa）/ 让他传送到你身边（/tpahere）。
     * <p>
     * dialogUI 的按钮没有左右键之分，所以用两步交互补齐 tpa / tpahere 两种方向，
     * 与箱子 UI 的「左键 tpa / 右键 tpahere」功能对等。
     */
    private static void openDirectionDialog(SfpMain plugin, Player player, Player target,
                                            TpaManager tpaManager, ClickCallback.Options options) {
        Map<String, String> ph = Map.of("name", target.getName());
        Component title = msg(plugin, "dialogui.tpa-direction-title",
                "<!i><aqua>玩家传送</aqua>", Map.of());
        Component body = msg(plugin, "dialogui.tpa-direction-body",
                "<!i><yellow>{name}</yellow> <gray>—— 选择传送方向</gray>", ph);

        ActionButton to = ActionButton.builder(
                        msg(plugin, "dialogui.tpa-to", "<!i><green>传送到他身边</green>", Map.of()))
                .tooltip(msg(plugin, "dialogui.tpa-to-hover",
                        "<!i><gray>等同 /tpa {name}</gray>", ph))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p && p.isOnline()) {
                        tpaManager.request(p, target, TpaManager.Type.TO);
                    }
                }, options))
                .build();
        ActionButton here = ActionButton.builder(
                        msg(plugin, "dialogui.tpa-here", "<!i><aqua>让他传送到你身边</aqua>", Map.of()))
                .tooltip(msg(plugin, "dialogui.tpa-here-hover",
                        "<!i><gray>等同 /tpahere {name}</gray>", ph))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p && p.isOnline()) {
                        tpaManager.request(p, target, TpaManager.Type.HERE);
                    }
                }, options))
                .build();

        Dialog dialog = Dialog.create(builder -> builder
                .empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(body)))
                        .build())
                .type(DialogType.confirmation(to, here)));

        player.showDialog(dialog);
    }

    private static ActionButton navButton(SfpMain plugin, String msgKey, String fallback,
                                          Player player, int page, ClickCallback.Options options) {
        return ActionButton.builder(plugin.getMessage(msgKey, fallback))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p && p.isOnline()) {
                        open(plugin, p, page);
                    }
                }, options))
                .build();
    }

    /** 取 messages.yml 里的文案（带占位符），解析为组件 */
    private static Component msg(SfpMain plugin, String key, String fallback, Map<String, String> ph) {
        return Messages.deserialize(Messages.apply(plugin.getRawMessage(key, fallback), ph));
    }
}
