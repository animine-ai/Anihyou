#!/usr/bin/env python3
"""Regression tests for diagnostic protections in merged R8 consumer configuration."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("performance_logging", Path(__file__).with_name("verify-performance-logging.py"))
logging = importlib.util.module_from_spec(spec)
spec.loader.exec_module(logging)


class PerformanceLoggingRulesTest(unittest.TestCase):
    def test_compose_and_coroutine_consumer_optimizations_are_allowed(self):
        rules = """
        -assumenosideeffects public class androidx.compose.runtime.ComposerKt {
            void sourceInformation(androidx.compose.runtime.Composer,java.lang.String);
        }
        -assumenosideeffects class kotlinx.coroutines.internal.MainDispatcherLoader {
            boolean FAST_SERVICE_LOADER_ENABLED;
        }
        -assumevalues class androidx.compose.runtime.tooling.ComposeStackTraceMode {
            private static boolean isMinified return true;
        }
        """
        self.assertEqual(3, len(logging.verify_assumptions(rules)))

    def test_explicit_wildcard_and_indirect_app_log_removal_are_rejected(self):
        for owner in ("android.util.Log", "android.util.L*", "**", "*", "com.axiel7.anihyou.**",
                      "**.AppLog", "**.SourceSeriesMatchingService",
                      "com.axiel7.anihyou.release.data.repository.SourceSeriesMatchingService"):
            with self.subTest(owner=owner), self.assertRaisesRegex(ValueError, "diagnostic data"):
                logging.verify_assumptions("-assumenosideeffects class " + owner + " { *; }")

    def test_forcing_logging_disabled_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "diagnostic data"):
            logging.verify_assumptions("-assumevalues class com.axiel7.anihyou.BuildConfig { boolean PERFORMANCE_LOGGING return false; }")

    def test_unrecognized_assumptions_fail_closed(self):
        for header in ("no-class-specification", "class !android.util.Log", "class <1>"):
            with self.subTest(header=header), self.assertRaises(ValueError):
                logging.verify_assumptions("-assumenosideeffects " + header + " { *; }")

    def test_keep_rules_do_not_allow_global_android_log_removal(self):
        rules = (logging.ROOT / "app/proguard-performance.pro").read_text()
        logging.verify_rules(rules)
        with self.assertRaisesRegex(ValueError, "Android log removal"):
            logging.verify_rules(rules + "\n-maximumremovedandroidloglevel 3\n")


if __name__ == "__main__":
    unittest.main()
