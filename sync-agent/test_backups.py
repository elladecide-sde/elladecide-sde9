import os
import shutil
import tempfile
import unittest

from snapshot_manager import SnapshotManager
from main import (
    AgentConfig,
    BACKUP_OPTIONS,
    DEFAULT_MAX_BACKUPS,
    backups_to_label,
    label_to_backups,
)


class TestBackupsConfigAndRotation(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.consumo_dir = os.path.join(self.test_dir, "consumo")
        self.backups_dir = os.path.join(self.test_dir, "backups")
        os.makedirs(self.consumo_dir, exist_ok=True)
        os.makedirs(self.backups_dir, exist_ok=True)

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_labels_and_parsing(self):
        self.assertEqual(label_to_backups("0 (OFF - Sin backups)"), 0)
        self.assertEqual(label_to_backups("OFF"), 0)
        self.assertEqual(label_to_backups("0"), 0)
        self.assertEqual(label_to_backups("1 copia"), 1)
        self.assertEqual(label_to_backups("3 copias (Predeterminado)"), 3)
        self.assertEqual(label_to_backups("5 copias"), 5)
        self.assertEqual(label_to_backups("15 copias"), 15)
        # Invalid / unknown fallback to DEFAULT_MAX_BACKUPS
        self.assertEqual(label_to_backups("invalido"), DEFAULT_MAX_BACKUPS)

        self.assertIn("OFF", backups_to_label(0))
        self.assertIn("3 copias", backups_to_label(3))

    def test_agent_config_persistence(self):
        cfg = AgentConfig(self.test_dir)
        # Default should be 3
        self.assertEqual(cfg.max_backups, DEFAULT_MAX_BACKUPS)

        # Change and save
        cfg.max_backups = 0
        cfg.save()

        # Reload
        cfg2 = AgentConfig(self.test_dir)
        self.assertEqual(cfg2.max_backups, 0)

        cfg2.max_backups = 5
        cfg2.save()

        cfg3 = AgentConfig(self.test_dir)
        self.assertEqual(cfg3.max_backups, 5)

    def test_snapshot_rotation_moves_and_limits(self):
        mgr = SnapshotManager(
            latest_dir=self.consumo_dir,
            backups_dir=self.backups_dir,
            max_backups=2,
        )

        # Simulate first snapshot in consumo
        with open(mgr.snapshot_path, "wb") as f:
            f.write(b"fake mdb data 1")
        self.assertTrue(os.path.exists(mgr.snapshot_path))

        # Rotate backup 1
        mgr.rotate_backup()
        # Should be moved, so snapshot_path in consumo should not exist anymore!
        self.assertFalse(os.path.exists(mgr.snapshot_path))
        backups = [f for f in os.listdir(self.backups_dir) if f.endswith(".mdb")]
        self.assertEqual(len(backups), 1)

        # Simulate second snapshot
        with open(mgr.snapshot_path, "wb") as f:
            f.write(b"fake mdb data 2")
        mgr.rotate_backup()
        backups = [f for f in os.listdir(self.backups_dir) if f.endswith(".mdb")]
        self.assertEqual(len(backups), 2)

        # Simulate third snapshot -> max_backups is 2, so it should prune oldest
        with open(mgr.snapshot_path, "wb") as f:
            f.write(b"fake mdb data 3")
        mgr.rotate_backup()
        backups = [f for f in os.listdir(self.backups_dir) if f.endswith(".mdb")]
        self.assertEqual(len(backups), 2)

    def test_snapshot_rotation_when_off(self):
        mgr = SnapshotManager(
            latest_dir=self.consumo_dir,
            backups_dir=self.backups_dir,
            max_backups=0,
        )
        with open(mgr.snapshot_path, "wb") as f:
            f.write(b"fake mdb data off")

        mgr.rotate_backup()
        # No backups should be created in backups_dir
        backups = [f for f in os.listdir(self.backups_dir) if f.endswith(".mdb")]
        self.assertEqual(len(backups), 0)
        # Snapshot in consumo should be cleaned up
        self.assertFalse(os.path.exists(mgr.snapshot_path))


if __name__ == "__main__":
    unittest.main()
