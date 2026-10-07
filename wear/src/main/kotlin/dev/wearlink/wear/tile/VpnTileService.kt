package dev.wearlink.wear.tile

import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.wearlink.core.R
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.Format
import dev.wearlink.core.vpn.ToggleActivity
import dev.wearlink.core.vpn.VpnController
import dev.wearlink.core.vpn.VpnState
import dev.wearlink.wear.MainActivity

/** Happ-style tile: one big button toggles the VPN with the default server. */
class VpnTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            WearLinkCore.init(application)
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(Timeline.fromLayoutElement(layout()))
                .build()
        }

    @Deprecated("Kept for renderers older than protolayout 1.3")
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = SuspendToFutureAdapter.launchFuture {
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(
                ICON_ID,
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_wearlink).build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun layout(): LayoutElementBuilders.LayoutElement {
        val state = VpnController.state.value
        val server = WearLinkCore.store.current.selectedServer
        val (buttonColor, statusColor) = when (state) {
            is VpnState.Connected -> GREEN to GREEN
            is VpnState.Connecting -> AMBER to AMBER
            is VpnState.Error -> GREY to RED
            VpnState.Stopped -> GREY to LIGHT_GREY
        }

        val toggle = Box.Builder()
            .setWidth(dp(BUTTON_SIZE))
            .setHeight(dp(BUTTON_SIZE))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(buttonColor))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(BUTTON_SIZE / 2)).build())
                            .build(),
                    )
                    .setClickable(clickable("toggle", ToggleActivity::class.java.name))
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription("Вкл/выкл VPN").build())
                    .build(),
            )
            .addContent(
                Image.Builder()
                    .setResourceId(ICON_ID)
                    .setWidth(dp(40f))
                    .setHeight(dp(40f))
                    .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(WHITE)).build())
                    .build(),
            )
            .build()

        val serverLabel = Text.Builder()
            .setText(server?.name ?: "Добавьте сервер")
            .setMaxLines(1)
            .setFontStyle(FontStyle.Builder().setSize(sp(14f)).setColor(argb(WHITE)).build())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable("servers", MainActivity::class.java.name))
                    .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(6f)).build())
                    .build(),
            )
            .build()

        val status = Text.Builder()
            .setText(Format.state(state))
            .setMaxLines(2)
            .setFontStyle(FontStyle.Builder().setSize(sp(13f)).setColor(argb(statusColor)).build())
            .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(
                Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(status)
                    .addContent(Spacer.Builder().setHeight(dp(8f)).build())
                    .addContent(toggle)
                    .addContent(Spacer.Builder().setHeight(dp(4f)).build())
                    .addContent(serverLabel)
                    .build(),
            )
            .build()
    }

    private fun clickable(id: String, activity: String) = ModifiersBuilders.Clickable.Builder()
        .setId(id)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(packageName)
                        .setClassName(activity)
                        .build(),
                )
                .build(),
        )
        .build()

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val ICON_ID = "vpn"
        private const val BUTTON_SIZE = 84f

        private const val GREEN = 0xFF2E7D32.toInt()
        private const val AMBER = 0xFFF9A825.toInt()
        private const val GREY = 0xFF3C3C3C.toInt()
        private const val RED = 0xFFEF5350.toInt()
        private const val LIGHT_GREY = 0xFFBDBDBD.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(VpnTileService::class.java)
        }
    }
}
