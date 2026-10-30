import unittest
from compras_extractor import normalizar_dni


class TestComprasExtractor(unittest.TestCase):
    def test_normalizar_dni(self):
        self.assertEqual(normalizar_dni("39.675.372"), "39675372")
        self.assertEqual(normalizar_dni(" 39675372 "), "39675372")
        self.assertEqual(normalizar_dni("20-39675372-1"), "20396753721")
        self.assertEqual(normalizar_dni(39675372), "39675372")
        self.assertEqual(normalizar_dni(None), "")
        self.assertEqual(normalizar_dni(""), "")


if __name__ == "__main__":
    unittest.main()
