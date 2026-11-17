import pathlib
import tempfile
import unittest

from analyze_ios_transitions import summarize


class TraceCoverageTests(unittest.TestCase):
    def summarize_samples(self, seconds):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            intervals = root / "intervals.xml"
            intervals.write_text('''<trace-query-result><node><schema>
                <column><mnemonic>start</mnemonic></column>
                <column><mnemonic>duration</mnemonic></column>
                <column><mnemonic>name</mnemonic></column>
                <column><mnemonic>start-message</mnemonic></column>
                </schema><row><start>2000000000</start><duration>6000000000</duration>
                <name>NativeUIWorkload</name><message fmt="editor-navigation / focus-schedule"/>
                </row></node></trace-query-result>''')
            cpu = root / "cpu.xml"
            rows = [f'''<row><sample-time>{int(second * 1e9)}</sample-time>
                <weight>1000000</weight><thread fmt="Main Thread"/>
                <tagged-backtrace><frame name="TestWork"/></tagged-backtrace></row>'''
                    for second in seconds]
            cpu.write_text("<trace-query-result>" + "".join(rows) + "</trace-query-result>")
            hitches = root / "hitches.xml"
            hitches.write_text('<trace-query-result><node><schema name="hitches-summary"/></node></trace-query-result>')
            return summarize(intervals, cpu, hitches)

    def test_complete_capture_preserves_measurements(self):
        result = self.summarize_samples([1, 3, 10])
        self.assertEqual(result["phases"][0]["sampledCpuMs"], 1)
        self.assertEqual(result["phases"][0]["mainThreadSamples"], 1)
        self.assertEqual(result["phases"][0]["maxHitchMs"], 0)
        self.assertEqual(result["cpuSampleSpanSeconds"], {"start": 1, "end": 10})

    def test_empty_or_truncated_stream_is_not_zero_work(self):
        for samples in ([], [1, 2.1], [6, 10]):
            with self.subTest(samples=samples), self.assertRaises(ValueError):
                self.summarize_samples(samples)

    def test_idle_interval_with_samples_before_and_after_is_allowed(self):
        result = self.summarize_samples([1, 10])
        self.assertEqual(result["phases"][0]["sampledCpuMs"], 0)

    def test_sparse_sampling_at_boundaries_is_allowed(self):
        result = self.summarize_samples([2.1, 7.9])
        self.assertEqual(result["phases"][0]["sampledCpuMs"], 2)


if __name__ == "__main__":
    unittest.main()
