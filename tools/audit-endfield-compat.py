"""Offline compatibility gate for the installed Endfield IL2CPP build."""
from pathlib import Path
import argparse
import hashlib
import json
import sys

KNOWN_BUILDS = {
    (
        'ab00f1c41e13edf9816f142b166e3c2536d5bc7707992c39e5ed52d1cc888e2c',
        '461321d9dca6a832bce3bd2390402efc7c83c145554e97a8058bb68fbec1c8ca',
    ): '1.6.3',
}

REQUIRED_ASSEMBLIES = (
    'Gameplay.Beyond.dll',
    'Input.Beyond.dll',
    'HG.RenderPipelines.Runtime.dll',
    'UnityEngine.CoreModule.dll',
    'UnityEngine.InputLegacyModule.dll',
)

# Runtime resolution still validates signatures, value sizes and field offsets.
# This list catches removed or renamed contracts before the game is launched.
REQUIRED_METADATA_NAMES = (
    'CameraManager', 'TailLateTick', 'PlayerController', 'GetMainCharacter',
    'Entity', 'get_position', 'get_movementComponent', 'get_alive',
    'get_inCinematic', 'MovementComponent', 'get_velocity',
    'GetCapsuleHeight', 'GetCapsuleRadius', 'KeyboardHandler', 'GetKey',
    'GetKeyDown', 'GetKeyUp', 'InputManager', '_OnAfterInputUpdate',
    'BattleHitReactionManager', 'Hit', 'HGRenderPipeline',
    'ExecuteRenderRequestCPP', 'RenderRequest', 'hgCamera', 'HGCamera',
    'TeleportProcessor', '_EnterClientPosAuthority',
    '_ExitClientPosAuthority', 'CharacterCollisionRaycast', 'TeleportTo',
    'get_rootCom', 'get_modelCom', 'EntityRenderHelper',
    'SetVisibleByRenderer', 'ResetVisibleByRenderer', 'GetModelGo',
)


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main(game, output):
    assembly = game / 'GameAssembly.dll'
    metadata = game / 'Endfield_Data/il2cpp_data/Metadata/global-metadata.dat'
    scripting = game / 'Endfield_Data/ScriptingAssemblies.json'
    missing_files = [str(path) for path in (assembly, metadata, scripting) if not path.is_file()]
    if missing_files:
        raise RuntimeError('Missing game files: ' + ', '.join(missing_files))

    assembly_hash = sha256(assembly)
    metadata_hash = sha256(metadata)
    release = KNOWN_BUILDS.get((assembly_hash, metadata_hash))
    metadata_bytes = metadata.read_bytes()
    scripting_text = scripting.read_text(encoding='utf-8-sig', errors='replace')
    missing_assemblies = [name for name in REQUIRED_ASSEMBLIES if name not in scripting_text]
    padded_metadata = b'\0' + metadata_bytes + b'\0'
    missing_names = [name for name in REQUIRED_METADATA_NAMES
                     if b'\0' + name.encode() + b'\0' not in padded_metadata]
    compatible = release is not None and not missing_assemblies and not missing_names
    result = {
        'game_directory': str(game.resolve()),
        'recognized_release': release,
        'game_assembly_sha256': assembly_hash,
        'metadata_sha256': metadata_hash,
        'required_assemblies': {'ok': not missing_assemblies, 'missing': missing_assemblies},
        'required_metadata_names': {'ok': not missing_names, 'missing': missing_names},
        'offline_compatible': compatible,
        'runtime_validation_required': True,
        'module': 'endcraft.gameplay38',
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if compatible else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--game-dir', type=Path, default=Path('D:/Arknights Endfield'))
    parser.add_argument('--output', type=Path,
                        default=Path(__file__).resolve().parents[1] / 'reports/endfield-compat.json')
    args = parser.parse_args()
    try:
        raise SystemExit(main(args.game_dir, args.output))
    except (OSError, RuntimeError) as error:
        parser.exit(2, str(error) + '\n')
