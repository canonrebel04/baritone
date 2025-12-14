UI/UX Modernization Specification: "Project Cyber-Glass"
1. Project Overview & Context

Goal: Complete overhaul of a Minecraft Utility Mod (Meteor Client Fork) interface. Current State: Functional but dated "ClickGUI" (column-based), standard Minecraft font, raw inputs, lack of visual feedback. Target State: A "Cyber-Glass" aesthetic—professional, responsive, highly animated, and user-centric. Technical Context:

    Rendering: Java (OpenGL/NanoVG/ImGui equivalent).

    Hardware: Optimized for high-refresh-rate displays (240Hz+). Animations must be fluid (delta-time scaled).

    Theme: Cybersecurity/Hacker focused (Dark mode, neon accents, acrylic blur).

2. Design Philosophy & "Vibe"

    Glassmorphism: The UI must feel like a layer of advanced glass floating over the game world. It should not just be a black box.

    Visual Hierarchy: Information density must be managed. Use size, color, and spacing to guide the eye.

    Micro-Interactions: Every click, hover, and toggle must have immediate, smooth visual feedback.

    Input Efficiency: reduce clicks required to reach key features (Modules, AI, Pathing).

3. Design Tokens (The "DNA")
3.1. Color Palette

    Primary Accent: #7C3AED (Electric Violet) to #C026D3 (Neon Fuchsia) - Use as a linear gradient for active states.

    Background (Surface): #09090b (Rich Black) with 0.85 opacity.

    Background (Overlay): #18181b (Zinc 900) with 0.6 opacity for inactive cards.

    Text (Primary): #FAFAFA (Off-white).

    Text (Secondary): #A1A1AA (Muted Grey).

    Status Indicators:

        Success/Active: #10B981 (Emerald).

        Warning/Thinking: #F59E0B (Amber).

        Danger/Error: #EF4444 (Red).

3.2. Typography

    Font Family: JetBrains Mono or Inter. (Monospace preferred for numeric data and code; Sans for UI labels).

    Weights:

        Headers: Bold (700)

        Body: Regular (400)

        Micro-text: Medium (500)

3.3. Spacing & Shape

    Corner Radius:

        Windows: 16px

        Cards/Modules: 12px

        Buttons: 8px

    Blur Strength: 15px Gaussian Blur (backdrop filter).

    Borders: 1px solid rgba(255, 255, 255, 0.1) (Inner stroke).

4. Component Architecture
4.1. The "Module Card" (Replaces Text Lists)

Instead of a list of text names, every module (e.g., "Kill Aura") is a Card.

    State [OFF]: Translucent grey background, grey icon, white text.

    State [HOVER]: Slight scale up (1.02x), border glows white.

    State [ON]: Background fills with Primary Gradient. Icon turns white. subtle "pulse" animation on the shadow.

    Quick Settings: Right-clicking the card flips it (3D transform) or expands a drawer downwards to show sliders/checkboxes without leaving the grid.

4.2. The Sidebar Navigation

Move tabs from the top to the Left Sidebar.

    Vertical orientation: Icons + Text labels.

    Collapsible: Can shrink to just Icons to save space.

    Categories: Combat, Movement, Player, Render, World, AI Operations, Baritone Control.

4.3. The "AI Command Center" (Chat Interface)

    Layout: Standard Chat UI (Bottom input, top history).

    Thinking Block: If the model outputs <think> or [THINK], render a Collapsible Accordion labeled "Reasoning Process" with a brain icon. Do not show raw tags.

    Code Blocks: Syntax highlighting for any code snippets returned by the AI.

    Quick Chips: Above the input bar, show context-aware chips: [Explain Config], [Optimize Route], [Scan Enemies].

4.4. The "Baritone Dashboard"

    Visualizer: A 2D radar/minimap widget showing the pathing goal.

    Queue Display: A horizontal timeline showing queued actions (Mine -> Walk -> Deposit) rather than a text list.

    Controls: Play/Pause/Stop buttons should look like media controls (large, centered).

5. UI Layout Specifications
Screen 1: The New ClickGUI

    Container: Centered Modal, 80% Width, 80% Height.

    Left Column (20%): Navigation Sidebar (Categories).

    Right Column (80%): The "Grid".

        Search Bar: Top right. Floating input field with Ctrl+F focus shortcut.

        Content: A CSS Grid-style layout of Module Cards.

        Filter: Small chips to filter by "Active", "Keybound", or "Hidden".

Screen 2: HUD Editor (Heads Up Display)

    Drag & Drop: Allow elements (FPS, Speed, Coords) to be dragged freely.

    Snap Lines: Show magnetic lines when elements align with each other or screen center.

    Live Preview: The background should be the actual game, not a darkened screen.

6. Animations & Transitions (Motion Design)

    Opening the Menu:

        Scale: 0.9 -> 1.0.

        Opacity: 0 -> 1.

        Curve: CubicBezier(0.16, 1, 0.3, 1) (Springy pop-up).

    Toggling a Module:

        Background fades in (Duration: 150ms).

        Icon rotates 360 degrees or bounces once.

    Scrolling: Smooth scrolling with inertia (momentum).

7. Technical Directives for the LLM (Implementation Guide)

Role: You are a Senior Graphics Programmer and UX Engineer.

Constraints:

    Rendering: Use the most performant rendering method available in the current mod loader (e.g., DrawContext in Fabric or Tessellator in Forge).

    Shaders: Implement a custom fragment shader for the "Blur" effect if the game engine supports it. If not, use a semi-transparent dark overlay.

    Data Binding: Ensure the UI is strictly decoupled from logic. The UI renders the state; it does not hold the state.

    Math: Use Lerp (Linear Interpolation) for all moving values (colors, positions, sizes) to ensure rendering is framerate independent.

Code Structure Requirement:

    Create a ThemeManager class to hold colors and fonts.

    Create a Component base class (x, y, width, height, render()).

    Create specific implementations: ModuleCard, Sidebar, Slider, ToggleSwitch.