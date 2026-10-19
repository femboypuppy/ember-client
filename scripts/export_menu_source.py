"""Export the full menu code, integration files and binary assets for review/sharing.

Run from any directory: python scripts/export_menu_source.py
The ZIP overlays an existing Ember checkout; it is not a second authentication mod.
"""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'build/menu-redesign'
JAVA = 'src/main/java/meteordevelopment/meteorclient/'


def files():
    selected = [ROOT / JAVA / 'gui/screens/EmberTitleScreen.java',
                ROOT / JAVA / 'gui/screens/EmberClientSettingsScreen.java',
                ROOT / JAVA / 'utils/render/EmberMenuWallpaper.java',
                ROOT / JAVA / 'utils/render/StartMenuLayout.java']
    selected += sorted((ROOT / JAVA / 'gui/screens/menu').glob('*.java'))
    for name in ('EmberMenuMinecraftMixin', 'EmberMenuScreenMixin', 'EmberMenuFpsMixin',
                 'EmberMenuSoundMixin', 'EmberMenuBackgroundMixin', 'TitleScreenMixin', 'GameRendererMixin'):
        selected.append(ROOT / JAVA / f'mixin/{name}.java')
    selected += [ROOT / name for name in ('src/main/resources/fabric.mod.json',
                                         'src/main/resources/ember-client.mixins.json',
                                         'build.gradle.kts', 'gradle/libs.versions.toml',
                                         'docs/start-menu.md', 'scripts/check_start_menu.py', 'scripts/check_rain_status.py',
                                         'scripts/export_menu_source.py')]
    selected += sorted(path for path in (ROOT / 'src/main/resources/assets/ember').rglob('*') if path.is_file())
    return selected


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    selected = files()
    paths = sorted(path.relative_to(ROOT).as_posix() for path in selected)
    tree = '\n'.join(paths)
    manifest = {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in selected}
    (OUTPUT / 'menu-files.txt').write_text(tree + '\n', encoding='utf-8')
    parts = ['```text\n' + tree + '\n```\n',
             'This is the complete implementation and integration code for the existing Ember Client repository. '
             'Binary PNG/TTF assets accompany it in `ember-menu-source.zip`, at the paths above. '
             'Existing Ember account/authentication services remain dependencies of this module. '
             'No Java file below is shortened or replaced with a snippet.\n',
             (ROOT / 'docs/start-menu.md').read_text(encoding='utf-8')]
    for path in selected:
        if path.suffix not in ('.java', '.json', '.kts', '.toml', '.py'):
            continue
        language = {'.java': 'java', '.json': 'json', '.kts': 'kotlin', '.toml': 'toml', '.py': 'python'}[path.suffix]
        parts.append('\n## ' + path.relative_to(ROOT).as_posix() + '\n\n```' + language + '\n' + path.read_text(encoding='utf-8').rstrip() + '\n```\n')
    parts.append('\n## Customization\n\n'
                 '- Colors, dimensions, timing: `MenuTheme.java`; dock spring behavior: `Dock.java`.\n'
                 '- Defaults and validation: `MenuConfig.java`.\n'
                 '- Layout and controls: `MenuSettingsScreen.java`, `MenuControl.java`.\n'
                 '- Wallpapers, native fonts, translations and click audio: the exact `assets/ember/` paths above.\n')
    guide = OUTPUT / 'ember-menu-full-code.md'
    guide.write_text('\n'.join(parts), encoding='utf-8')
    with zipfile.ZipFile(OUTPUT / 'ember-menu-source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in selected:
            archive.write(path, path.relative_to(ROOT).as_posix())
        archive.writestr('MENU-FILES.txt', tree + '\n')
        archive.writestr('MENU-SHA256.json', json.dumps(manifest, indent=2))
        archive.write(guide, guide.name)
    print(f'Exported {len(selected)} complete files, full-code guide and assets to {OUTPUT}')


if __name__ == '__main__':
    main()
