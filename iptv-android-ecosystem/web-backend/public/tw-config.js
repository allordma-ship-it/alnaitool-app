// Tailwind Play CDN theme – mirrors the AuraStream TV palette.
window.tailwind = window.tailwind || {};
tailwind.config = {
  theme: {
    extend: {
      colors: {
        canvas: '#080A0F',
        surface: '#151A23',
        surface2: '#1C2330',
        line: '#263042',
        neon: '#00E5FF',
        muted: '#8A97AD',
      },
      fontFamily: {
        sans: ['Inter', 'Noto Sans Arabic', 'system-ui', 'sans-serif'],
      },
      boxShadow: {
        neon: '0 0 0 2px #00E5FF, 0 0 24px rgba(0,229,255,.35)',
      },
    },
  },
};
