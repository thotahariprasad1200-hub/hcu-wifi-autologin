package edu.uoh.hcuwifi;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * Quick Settings Tile service for 1-Tap HCU Wi-Fi login directly from the notification shade.
 * Runs on Realme 7 (Android 12/13) with zero external dependencies.
 */
public class HcuTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        Tile tile = getQsTile();
        if (tile == null) return;

        // Visual feedback immediately
        tile.setState(Tile.STATE_ACTIVE);
        tile.setLabel(getString(R.string.tile_logging_in));
        tile.updateTile();

        Toast.makeText(this, "HCU Wi-Fi: Connecting to Sophos...", Toast.LENGTH_SHORT).show();

        // Run login asynchronously
        HcuAuthHelper.loginAsync(this, result -> {
            Tile currentTile = getQsTile();
            if (currentTile == null) return;

            if (result.isSuccess()) {
                currentTile.setState(Tile.STATE_ACTIVE);
                currentTile.setLabel(getString(R.string.tile_online));
                currentTile.setSubtitle("Logged in");
                Toast.makeText(this, "HCU Wi-Fi: " + result.message, Toast.LENGTH_SHORT).show();
            } else {
                currentTile.setState(Tile.STATE_INACTIVE);
                currentTile.setLabel(getString(R.string.tile_offline));
                currentTile.setSubtitle("Tap to retry");
                Toast.makeText(this, "HCU Wi-Fi: " + result.message, Toast.LENGTH_LONG).show();
            }
            currentTile.updateTile();
        });
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        HcuAuthHelper.probeAsync(result -> {
            Tile currentTile = getQsTile();
            if (currentTile == null) return;

            if (result.status == HcuAuthHelper.AuthResult.Status.ALREADY_ONLINE) {
                currentTile.setState(Tile.STATE_ACTIVE);
                currentTile.setLabel(getString(R.string.tile_online));
                currentTile.setSubtitle("Active");
            } else {
                currentTile.setState(Tile.STATE_INACTIVE);
                currentTile.setLabel(getString(R.string.tile_label));
                currentTile.setSubtitle("Tap to login");
            }
            currentTile.updateTile();
        });
    }
}
