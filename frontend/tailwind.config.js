/** @type {import('tailwindcss').Config} */
// Design tokens. "Ink" replaces Tailwind's slate everywhere: a cooler graphite whose grey text steps are lifted
// for contrast (slate-400 ≈ 7.5:1, slate-500 ≈ 4.7:1 on slate-950). "Signal" cyan is the single brand accent; amber,
// rose and emerald stay reserved for risk levels.
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        slate: {
          50: '#f4f7fb',
          100: '#e6ecf3',
          200: '#cfd8e3',
          300: '#b0bccb',
          400: '#94a2b6',
          500: '#76859b',
          600: '#525f73',
          700: '#3a4454',
          800: '#252d3a',
          850: '#1c2330',
          900: '#141a24',
          950: '#0b0f16',
        },
        cyan: {
          50: '#ecfeff',
          100: '#cbfbfe',
          200: '#9af4fb',
          300: '#5fe8f6',
          400: '#28d5ec',
          500: '#0cb9d6',
          600: '#0894b2',
          700: '#0d7590',
          800: '#135f75',
          900: '#154f63',
          950: '#073444',
        },
        brand: {
          50: '#ecfeff',
          100: '#cbfbfe',
          500: '#0cb9d6',
          600: '#0894b2',
          700: '#0d7590',
        },
      },
      fontFamily: {
        sans: ['"IBM Plex Sans"', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif'],
        display: ['"Sora Variable"', '"IBM Plex Sans"', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono Variable"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      boxShadow: {
        glow: '0 0 0 1px rgba(40, 213, 236, 0.25), 0 8px 30px -8px rgba(12, 185, 214, 0.45)',
        panel: '0 1px 0 0 rgba(255, 255, 255, 0.03) inset, 0 20px 40px -24px rgba(0, 0, 0, 0.6)',
      },
      keyframes: {
        'fade-up': { from: { opacity: '0', transform: 'translateY(6px)' }, to: { opacity: '1', transform: 'none' } },
        ripple: { '0%': { opacity: '0.55', transform: 'scale(0.6)' }, '100%': { opacity: '0', transform: 'scale(1.6)' } },
      },
      animation: {
        'fade-up': 'fade-up 380ms cubic-bezier(0.2, 0.7, 0.2, 1) both',
        ripple: 'ripple 3.2s cubic-bezier(0.2, 0.6, 0.3, 1) infinite',
      },
    },
  },
  plugins: [],
};
