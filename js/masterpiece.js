/**
 * NOTES+ MASTERPIECE INTERACTIVE CONTROLLER
 * Micro-interactions, Theme Engine, 3D Tilt, Dock Magnification, Studio & Feedback
 */

(function () {
  'use strict';

  // =========================================================================
  // 1. Theme Engine (Dark / Light Mode)
  // =========================================================================
  const THEME_KEY = 'np_site_theme';
  
  function getPreferredTheme() {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved === 'dark' || saved === 'light') return saved;
    return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem(THEME_KEY, theme);
    const metaTheme = document.querySelector('meta[name="theme-color"]');
    if (metaTheme) {
      metaTheme.setAttribute('content', theme === 'dark' ? '#09090b' : '#f8fafc');
    }
  }

  /**
   * The new theme spreads out in a circle from where it was asked for (the button, or the
   * screen's centre for the T key), like Magic UI's animated theme toggler: a View Transition
   * whose new snapshot is revealed by a growing clip-path. Browsers without View Transitions,
   * and anyone who asked for less motion, just switch.
   */
  function toggleTheme(e) {
    const current = document.documentElement.getAttribute('data-theme') || 'dark';
    const next = current === 'dark' ? 'light' : 'dark';
    const still = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (!document.startViewTransition || still) { applyTheme(next); return; }
    const btn = e && e.currentTarget && e.currentTarget.getBoundingClientRect ? e.currentTarget.getBoundingClientRect() : null;
    const x = btn ? btn.left + btn.width / 2 : innerWidth / 2, y = btn ? btn.top + btn.height / 2 : innerHeight / 2;
    const r = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
    const t = document.startViewTransition(() => applyTheme(next));
    t.ready.then(() => {
      document.documentElement.animate(
        { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${r}px at ${x}px ${y}px)`] },
        { duration: 650, easing: 'cubic-bezier(0.65, 0, 0.35, 1)', pseudoElement: '::view-transition-new(root)' });
    }).catch(() => {});
  }

  // Bind all theme toggles
  document.querySelectorAll('[data-action="toggle-theme"]').forEach(btn => {
    btn.addEventListener('click', toggleTheme);
  });

  // Apply immediately on parse
  applyTheme(getPreferredTheme());

  // Listen to OS scheme changes if user hasn't explicitly overridden
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', e => {
    if (!localStorage.getItem(THEME_KEY)) {
      applyTheme(e.matches ? 'dark' : 'light');
    }
  });

  // =========================================================================
  // 2. Smoothed-Out Floating Navbar Scroll Listener & Link Tracker
  // =========================================================================
  const navWrap = document.querySelector('.navbar-wrap');
  let lastScrollY = window.scrollY;

  window.addEventListener('scroll', () => {
    const currentY = window.scrollY;
    if (navWrap) {
      if (currentY > 30) {
        navWrap.classList.add('scrolled');
      } else {
        navWrap.classList.remove('scrolled');
      }
    }
    lastScrollY = currentY;
  }, { passive: true });

  // Active section indicator via IntersectionObserver
  const sections = document.querySelectorAll('section[id], header[id]');
  const navLinks = document.querySelectorAll('.nav-link[href^="#"]');

  if (sections.length > 0 && navLinks.length > 0) {
    const observer = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          const id = entry.target.getAttribute('id');
          navLinks.forEach(link => {
            if (link.getAttribute('href') === `#${id}`) {
              link.classList.add('active');
            } else {
              link.classList.remove('active');
            }
          });
        }
      });
    }, { rootMargin: '-20% 0px -70% 0px' });

    sections.forEach(s => observer.observe(s));
  }

  // =========================================================================
  // 3. Animated macOS-Inspired Floating Dock Physics
  // =========================================================================
  const dock = document.querySelector('.floating-dock');
  const dockItems = document.querySelectorAll('.dock-item');

  if (dock && dockItems.length > 0) {
    dock.addEventListener('mousemove', (e) => {
      const mouseX = e.clientX;
      dockItems.forEach(item => {
        const rect = item.getBoundingClientRect();
        const itemCenter = rect.left + rect.width / 2;
        const dist = Math.abs(mouseX - itemCenter);
        const maxDist = 120;

        if (dist < maxDist) {
          const scale = 1 + 0.28 * Math.cos((dist / maxDist) * (Math.PI / 2));
          item.style.transform = `translateY(-${(scale - 1) * 28}px) scale(${scale})`;
        } else {
          item.style.transform = '';
        }
      });
    });

    dock.addEventListener('mouseleave', () => {
      dockItems.forEach(item => {
        item.style.transform = '';
      });
    });
  }

  // =========================================================================
  // 4. Interactive 3D Device Showcase (Card Tilt & Tabs)
  // =========================================================================
  const showcaseCard = document.querySelector('.showcase-card');
  const showcaseTabs = document.querySelectorAll('.showcase-tab');
  const showcaseScreens = document.querySelectorAll('.showcase-screen img');

  // Tab switching
  showcaseTabs.forEach(tab => {
    tab.addEventListener('click', () => {
      showcaseTabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      const targetScreen = tab.getAttribute('data-target');

      showcaseScreens.forEach(img => {
        if (img.getAttribute('data-screen') === targetScreen) {
          img.classList.add('active');
        } else {
          img.classList.remove('active');
        }
      });
    });
  });

  // 3D Perspective Tilt on mouse move
  if (showcaseCard) {
    showcaseCard.addEventListener('mousemove', (e) => {
      const rect = showcaseCard.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const centerX = rect.width / 2;
      const centerY = rect.height / 2;

      const rotateX = ((y - centerY) / centerY) * -5;
      const rotateY = ((x - centerX) / centerX) * 5;

      showcaseCard.style.transform = `rotateX(${rotateX}deg) rotateY(${rotateY}deg)`;
      showcaseCard.style.setProperty('--mouse-x', `${(x / rect.width) * 100}%`);
      showcaseCard.style.setProperty('--mouse-y', `${(y / rect.height) * 100}%`);
    });

    showcaseCard.addEventListener('mouseleave', () => {
      showcaseCard.style.transform = 'rotateX(0deg) rotateY(0deg)';
    });
  }

  // =========================================================================
  // 5. Interactive Typography Studio Widget (Bento Card 1)
  // =========================================================================
  const previewBox = document.getElementById('studioPreview');
  const btnBold = document.getElementById('btnStudioBold');
  const btnItalic = document.getElementById('btnStudioItalic');
  const btnUnderline = document.getElementById('btnStudioUnderline');
  const kerningRange = document.getElementById('studioKerning');
  const leadingRange = document.getElementById('studioLeading');
  const swatches = document.querySelectorAll('.color-swatch');

  if (previewBox) {
    if (btnBold) {
      btnBold.addEventListener('click', () => {
        btnBold.classList.toggle('active');
        previewBox.style.fontWeight = btnBold.classList.contains('active') ? '700' : '400';
      });
    }
    if (btnItalic) {
      btnItalic.addEventListener('click', () => {
        btnItalic.classList.toggle('active');
        previewBox.style.fontStyle = btnItalic.classList.contains('active') ? 'italic' : 'normal';
      });
    }
    if (btnUnderline) {
      btnUnderline.addEventListener('click', () => {
        btnUnderline.classList.toggle('active');
        previewBox.style.textDecoration = btnUnderline.classList.contains('active') ? 'underline' : 'none';
      });
    }
    if (kerningRange) {
      kerningRange.addEventListener('input', (e) => {
        previewBox.style.letterSpacing = `${e.target.value}px`;
      });
    }
    if (leadingRange) {
      leadingRange.addEventListener('input', (e) => {
        previewBox.style.lineHeight = e.target.value;
      });
    }
    swatches.forEach(swatch => {
      swatch.addEventListener('click', () => {
        swatches.forEach(s => s.classList.remove('active'));
        swatch.classList.add('active');
        const color = swatch.getAttribute('data-color');
        previewBox.style.color = color;
      });
    });
  }

  // =========================================================================
  // 6. In-Page 5-Star Feedback Form Submitter
  // =========================================================================
  const stars = document.querySelectorAll('.rating-star');
  let selectedRating = 5;
  let selectedCategory = 'Feature Suggestion';

  stars.forEach(star => {
    star.addEventListener('click', () => {
      selectedRating = parseInt(star.getAttribute('data-val'), 10) || 5;
      updateStars(selectedRating);
    });
    star.addEventListener('mouseenter', () => {
      const hoverVal = parseInt(star.getAttribute('data-val'), 10) || 5;
      highlightStars(hoverVal);
    });
  });

  const starRow = document.querySelector('.star-rating-row');
  if (starRow) {
    starRow.addEventListener('mouseleave', () => {
      updateStars(selectedRating);
    });
  }

  function highlightStars(val) {
    stars.forEach(s => {
      const starVal = parseInt(s.getAttribute('data-val'), 10) || 0;
      if (starVal <= val) {
        s.classList.add('active');
      } else {
        s.classList.remove('active');
      }
    });
  }

  function updateStars(val) {
    highlightStars(val);
  }

  // Category pills
  const catPills = document.querySelectorAll('.cat-pill');
  catPills.forEach(pill => {
    pill.addEventListener('click', () => {
      catPills.forEach(p => p.classList.remove('active'));
      pill.classList.add('active');
      selectedCategory = pill.textContent.trim();
    });
  });

  // Submit Feedback to Firebase RTDB
  const feedbackBtn = document.getElementById('btnSubmitFeedback');
  const feedbackMsg = document.getElementById('feedbackMessage');
  const feedbackEmail = document.getElementById('feedbackEmail');
  const feedbackStatus = document.getElementById('feedbackStatus');

  if (feedbackBtn && feedbackMsg) {
    feedbackBtn.addEventListener('click', async () => {
      const message = feedbackMsg.value.trim();
      if (!message) {
        feedbackMsg.focus();
        feedbackMsg.style.borderColor = 'var(--rose-500)';
        return;
      }

      feedbackBtn.disabled = true;
      feedbackBtn.textContent = 'Submitting...';

      const email = feedbackEmail ? feedbackEmail.value.trim() : '';
      const now = Date.now();
      const fid = 'fb_' + now;

      const payload = {
        uid: 'web_visitor_' + Math.random().toString(36).slice(2, 10),
        username: email ? email.split('@')[0] : 'visitor',
        displayName: email ? email.split('@')[0] : 'Web Visitor',
        email: email,
        rating: selectedRating,
        type: selectedCategory,
        message: message,
        platform: 'Website Landing Page',
        version: '16.0',
        ts: now,
        date: new Date().toLocaleDateString()
      };

      try {
        const DB_URL = 'https://notes-plus-88985-default-rtdb.asia-southeast1.firebasedatabase.app';
        await fetch(`${DB_URL}/feedback/${fid}.json`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });

        if (feedbackStatus) {
          feedbackStatus.innerHTML = `
            <div style="padding: 16px; background: rgba(16, 185, 129, 0.15); border: 1px solid var(--emerald-500); border-radius: 12px; color: var(--emerald-400); text-align: center; margin-top: 14px; font-weight: 600;">
              ✨ Thank you! Your ${selectedRating}★ feedback has been delivered straight to the core team.
            </div>
          `;
        }
        feedbackMsg.value = '';
        if (feedbackEmail) feedbackEmail.value = '';
        feedbackBtn.textContent = 'Feedback Sent!';
      } catch (err) {
        if (feedbackStatus) {
          feedbackStatus.innerHTML = `
            <div style="padding: 16px; background: rgba(244, 63, 94, 0.15); border: 1px solid var(--rose-500); border-radius: 12px; color: var(--rose-500); text-align: center; margin-top: 14px;">
              Couldn't send feedback right now. Please try again!
            </div>
          `;
        }
        feedbackBtn.disabled = false;
        feedbackBtn.textContent = 'Submit Feedback';
      }
    });
  }

  // =========================================================================
  // 7. One-Click Copy Buttons (SHA-256 Hashes)
  // =========================================================================
  document.querySelectorAll('[data-copy-hash]').forEach(btn => {
    btn.addEventListener('click', () => {
      const hash = btn.getAttribute('data-copy-hash');
      if (hash) {
        navigator.clipboard.writeText(hash).then(() => {
          const original = btn.innerHTML;
          btn.innerHTML = '✓ Copied';
          btn.style.color = 'var(--emerald-400)';
          setTimeout(() => {
            btn.innerHTML = original;
            btn.style.color = '';
          }, 2000);
        });
      }
    });
  });

  // Global Keyboard Shortcuts
  window.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
    if (e.key === 't' || e.key === 'T') {
      toggleTheme();
    }
  });

})();
