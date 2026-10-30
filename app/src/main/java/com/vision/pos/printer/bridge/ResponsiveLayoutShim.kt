package com.vision.pos.printer.bridge

/**
 * Login-only safety net for hosted frontends that still use the old centered
 * card. The permanent responsive layouts live in the web frontend; this keeps
 * login reachable while that deployment is rolling out. CSS also applies when
 * React renders the login route after the page-finished callback.
 */
object ResponsiveLayoutShim {
    val JS: String = """
        (function () {
          'use strict';
          function install() {
            if (document.getElementById('pos-responsive-login')) return;
            var parent = document.head || document.documentElement;
            if (!parent) return;
            var style = document.createElement('style');
            style.id = 'pos-responsive-login';
            style.textContent =
              '#root > section.pos-login,' +
              '#root > section.flex.h-dvh.items-center.justify-center {' +
                'height:100%;height:100dvh;display:flex;flex-direction:column;' +
                'align-items:center;justify-content:flex-start;overflow-y:auto;' +
              '}' +
              '#root > section.pos-login > div,' +
              '#root > section.flex.h-dvh.items-center.justify-center > div {' +
                'flex-shrink:0;margin-top:auto;margin-bottom:auto;' +
              '}';
            parent.appendChild(style);
          }
          install();
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', install, { once: true });
          }
        })();
    """.trimIndent()
}