package domain

import "testing"

func TestLastInitialSkipsArabicArticle(t *testing.T) {
	cases := map[string]string{"الحلبي": "ح.", "العلي": "ع.", "النجار": "ن.", "خطيب": "خ.", "Tester": "T.", "ال": "ا.", "": ""}
	for in, want := range cases {
		if got := lastInitial(in); got != want {
			t.Errorf("lastInitial(%q) = %q, want %q", in, got, want)
		}
	}
}
