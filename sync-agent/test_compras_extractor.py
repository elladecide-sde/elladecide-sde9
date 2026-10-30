import unittest
from datetime import datetime
from compras_extractor import normalizar_dni, parsear_fecha_rango


class TestComprasExtractor(unittest.TestCase):
    def test_normalizar_dni(self):
        self.assertEqual(normalizar_dni("39.675.372"), "39675372")
        self.assertEqual(normalizar_dni(" 39675372 "), "39675372")
        self.assertEqual(normalizar_dni("20-39675372-1"), "20396753721")
        self.assertEqual(normalizar_dni(39675372), "39675372")
        self.assertEqual(normalizar_dni(None), "")
        self.assertEqual(normalizar_dni(""), "")

    def test_parsear_fecha_rango(self):
        ref = datetime(2026, 10, 30, 12, 0, 0)

        # 48hs
        d48, h48 = parsear_fecha_rango("48h", referencia=ref)
        self.assertEqual(h48, ref)
        self.assertEqual((h48 - d48).total_seconds(), 48 * 3600)

        # 7d
        d7d, h7d = parsear_fecha_rango("7d", referencia=ref)
        self.assertEqual((h7d - d7d).days, 7)

        # 1m
        d1m, h1m = parsear_fecha_rango("1m", referencia=ref)
        self.assertEqual((h1m - d1m).days, 30)

        # Personalizada
        d_cust, h_cust = parsear_fecha_rango(
            "personalizada",
            desde_custom="2026-08-01T00:00:00",
            hasta_custom="2026-08-10T23:59:59",
            referencia=ref,
        )
        self.assertEqual(d_cust, datetime(2026, 8, 1, 0, 0, 0))
        self.assertEqual(h_cust, datetime(2026, 8, 10, 23, 59, 59))


if __name__ == "__main__":
    unittest.main()
