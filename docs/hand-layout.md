# Hand layout

Open **Render → Hand View**. The Ember popup and native settings both offer:

- **Make symmitrical**: choose **main → off** or **off → main**. The chosen source stays unchanged; the destination gets mirrored X position, mirrored Y/Z rotation, and the same scale.
- **Open hand editor**: select either hand and drag its axes to move, rotate or scale it. The reference hands follow your player's left/right main-arm preference.

The viewport uses simple reference hands to make the local transforms readable. It does not reproduce each held item's model, equip animation or vanilla grip. Hold **Space** in a world to hide the editor and check the actual first-person result. Hand View is temporarily enabled while editing and returns to its previous enabled state when you leave. Enable it afterward if you want to keep using the saved layout.

Controls:

| Action | Control |
| --- | --- |
| Move / Rotate / Scale | Buttons or G / R / S |
| Constrain to an axis | Drag a colored handle or rotation ring |
| Move across the viewing plane | Drag the white center handle |
| Fine adjustment | Hold Shift while dragging |
| Snap | Hold Ctrl (0.1 position/scale, 15° rotation) |
| Orbit / zoom reference view | Middle drag / mouse wheel |
| Type an exact component | Click X, Y or Z value, Enter to confirm |
| Clear typed value | Ctrl+A |
| Undo / Redo | Buttons or Ctrl+Z / Ctrl+Shift+Z |
| Cancel a drag | Right click or Escape |
| Check real hands | Hold Space |

Rotation and scaling pivot around the displayed hand, compensating its local position because Hand View applies translation after rotation and scale. Editor position controls allow ±10 blocks and scale controls allow 0.05–5. Existing settings outside these ranges remain unchanged until edited. Mirroring preserves the source's exact values without clamping.

**Apply** keeps the layout. **Cancel**, Escape or leaving the editor for another screen restores both hands to their original layout. Opening the source-choice dialog does not apply or discard the editing session. On shorter windows, scroll over the controls to reach the lower tools; Apply and Cancel remain fixed at the bottom.

**Include empty arms** applies the hand layout to empty hands too, before the existing shared Arm settings. Vanilla animation and different item grips can still make different held items look asymmetric even when their custom layout values are mirrored.
