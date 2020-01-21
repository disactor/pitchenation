package com.disactor.pitchenation;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.io.jvm.JVMAudioInputStream;
import be.tarsos.dsp.pitch.PitchDetectionHandler;
import be.tarsos.dsp.pitch.PitchDetectionResult;
import be.tarsos.dsp.pitch.PitchProcessor;
import be.tarsos.dsp.pitch.PitchProcessor.PitchEstimationAlgorithm;
import org.jfugue.player.Player;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class Pitchenation extends JFrame implements PitchDetectionHandler {

    private static final String[] scale = new String[]{"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final int[] octaves = new int[]{3, 4, 5, 6, 7};

//    Do0("Do0", "C0", 16.35),
//    Si0("Si0", "B0", 30.87),
//    Do1("Do1", "C1", 32.7),
//    Do2("Do2", "C2", 65.41),
//    Do3("Do3", "C3", 130.81),
//    Do4("Do4", "C4", 261.63),

//    Do5("Do5", "C5", 523.25),
//    Di5("Di5", "C#5", 554.37),
//    Re5("Re5", "D5", 587.33),
//    Ri5("Ri5", "D#5", 622.25),
//    Mi5("Mi5", "E5", 659.25),
//    Fa5("Fa5", "F5", 698.46),
//    Fi5("Fi5", "F#5", 739.99),
//    So5("So5", "G5", 783.99),
//    Se5("Se5", "G#5", 830.61),
//    La5("La5", "A5", 880),
//    Li5("Li5", "A#5", 932.33),
//    Si5("Si5", "B5", 987.77),

//    Do6("Do6", "C6", 1046.5),
//    Do7("Do7", "C7", 2093),
//    Do8("Do8", "C8", 4186.01),
//    Si8("Si8", "B8", 7902.13);

    // https://www.nature.com/articles/s41598-017-18150-y/figures/2
    private static final Map<String, Color> chromaToColor = new LinkedHashMap<String, Color>() {{
        put("Do", new Color(255, 0, 0)); // Red 620-750 nm (400-484 THz frequency)
        put("Di", new Color(127, 0, 0)); // Bordo
        put("Re", new Color(255, 127, 0)); // Orange 590-620 nm
        put("Ri", new Color(127, 127, 0)); // Olive
        put("Mi", new Color(255, 255, 0)); // Yellow 570-590 nm
        put("Fa", new Color(0, 255, 0));  // Green 495-570 nm
        put("Fi", new Color(0, 127, 127)); // Pine
        put("So", new Color(0, 255, 255)); // Cyan
        put("Se", new Color(0, 0, 127)); // Navy
        put("La", new Color(0, 0, 255)); // Blue: 450-495 nm
        put("Li", new Color(255, 0, 255)); // Magenta
        put("Si", new Color(127, 0, 255)); // Violet: 380-450 nm (688-789 THz frequency)
    }};

    private static final Map<String, Pitch> pitchByNote = Stream.of(Pitch.values())
            .collect(Collectors.toMap(Pitch::getNote, pitch -> pitch));
    private static final Map<Integer, Pitch> pitchByOrdinal = Stream.of(Pitch.values())
            .collect(Collectors.toMap(Pitch::ordinal, pitch -> pitch));
    private static final PitchEstimationAlgorithm defaultPitchAlgo = PitchEstimationAlgorithm.MPM;
    private static final Pitch playOnSuccess = Pitch.Do4;

    private final JPanel riddlePanel;
    private final JPanel guessPanel;
    private final JLabel guessLabel;
    private final JLabel riddleLabel;
    private final JPanel chromaPanel;

    private final Queue<Pitch> riddleQueue = new ArrayBlockingQueue<>(scale.length);
    private final AtomicReference<Pitch> riddle = new AtomicReference<>(null);
    private final AtomicReference<Pitch> guess = new AtomicReference<>(null);
    private final Player player = new Player();
    private final Random random = new Random();
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile AudioDispatcher dispatcher;
    private volatile Mixer currentMixer;
    private volatile PitchEstimationAlgorithm algo = defaultPitchAlgo;

    private void play(float pitch, float probability, double rms, Pitch guess) {
        if (isRunning.get()) {
            while (this.riddle.get() == null) {
                if (riddleQueue.size() == 0) {
                    List<Pitch> riddles = Arrays.stream(scale)
                            .map(letter -> letter + (random.nextInt(octaves.length - octaves[0]) + octaves[0]))
                            .map(pitchByNote::get)
                            .collect(Collectors.toList());
                    Collections.shuffle(riddles);
                    out(" " + riddles + " are the next riddles");
                    riddleQueue.addAll(riddles);
                }
                Pitch riddle = riddleQueue.poll();
                if (riddle != null) {
                    out(" [" + riddle.getEchroma() + "] is the new riddle");
                    this.riddle.set(riddle);
                    SwingUtilities.invokeLater(() -> {
                        Color riddleColor = chromaToColor.get(riddle.getChroma());
                        riddleLabel.setText(" " + riddle.getChroma() + " ");
                        riddlePanel.setBackground(riddleColor);
                        guessLabel.setText("    ");
                        guessPanel.setBackground(null);
                        guessLabel.setVisible(false);
                        chromaPanel.setBackground(null);
                        chromaPanel.setBorder(null);
                    });
                    player.play(riddle.getNote());
                }
            }
        }

        if (guess != null) {
            Pitch riddle = this.riddle.get();
            SwingUtilities.invokeLater(() -> updateGuess(pitch, guess));
            if (riddle != null) {
                if (isRunning.get()) {
                    out(String.format("  [%s] %s  -  [%s] %s [%.2fHz] %.2fHz - %.2f | %.5f", riddle.getChroma(), guess.getChroma(), riddle.getEchroma(), guess.getEchroma(), riddle.getPitch(), pitch, probability, rms));
                }
                if (guess.getChroma().equals(riddle.getChroma()) && isRunning.get()) {
                    SwingUtilities.invokeLater(() -> riddleLabel.setText(" " + riddle.getChroma() + " "));
                    this.riddle.set(null);
                    this.guess.set(null);

                    player.play(riddle.getNote());
                    SwingUtilities.invokeLater(() -> {
                        riddleLabel.setText(" " + playOnSuccess.getChroma() + " ");
                        riddlePanel.setBackground(chromaToColor.get(playOnSuccess.getChroma()));
                    });
                    player.play(playOnSuccess.getNote());
                    play(-1, 0, 0, guess);
                } else {
                    if (isRunning.get()) {
                        player.play(riddle.getNote());
                    }
                }
            }
        }
    }

    private void updateGuess(float pitch, Pitch guess) {
        double diff = pitch - guess.getPitch();
        guessLabel.setVisible(true);
        guessLabel.setText(" " + guess.getChroma() + " ");

        Pitch flat = pitchByOrdinal.get(guess.ordinal() - 1);
        Pitch sharp = pitchByOrdinal.get(guess.ordinal() + 1);
        if (flat != null && sharp != null) { // Can be null when out of range, this could have been done better, but meh
            Pitch pitchy;
            if (diff < 0) {
                pitchy = flat;
            } else {
                pitchy = sharp;
            }
            Color pitchyColor = chromaToColor.get(pitchy.getChroma());
            double pitchyDiff = Math.abs(guess.getPitch() - pitchy.getPitch());
            double ratio = Math.abs(diff) / pitchyDiff;
            int width = (int) (ratio * 50) + 1;
            if (!isRunning.get()) {
                out(String.format(" %s | pitch=%.2fHz | diff=%.2f | pitchy=%.2f | ratio=%.2f | width=%s", guess.getEchroma(), pitch, diff, pitchyDiff, ratio, width));
            }
            Color chromaColor = chromaToColor.get(guess.getChroma());
            chromaPanel.setBackground(chromaColor);
            Color guessColor = interpolate(ratio, chromaColor, pitchyColor);
            guessPanel.setBackground(guessColor);

            Color borderColor = interpolate(ratio * 20, chromaColor, pitchyColor);
            chromaPanel.setBorder(BorderFactory.createLineBorder(borderColor));
        }
    }

    private Color interpolate(double ratio, Color color1, Color color2) {
        if (ratio > 1) {
            ratio = 1;
        }
        int red = (int) (color2.getRed() * ratio + color1.getRed() * (1 - ratio));
        int green = (int) (color2.getGreen() * ratio + color1.getGreen() * (1 - ratio));
        int blue = (int) (color2.getBlue() * ratio + color1.getBlue() * (1 - ratio));
        return new Color(red, green, blue);
    }

    private Pitch matchPitch(float pitch) {
        Pitch guess = null;
        for (Pitch aPitch : Pitch.values()) {
            double diff = Math.abs(aPitch.getPitch() - pitch);
            if (diff < 5) {
//                out("         diff: " + aPitch.getPitch() + "-" + pitch + "=" + diff);
                if (guess != null) {
                    if (Math.abs(guess.getPitch() - pitch) < diff) {
                        aPitch = guess;
//                        out("         better diff: " + aPitch.getPitch() + "-" + pitch + "=" + diff);
                    }
                }
                guess = aPitch;
            }
        }
        return guess;
    }

    public static void main(String... strings) throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(() -> {
            UIManager.put("control", new Color(128, 128, 128));
            UIManager.put("info", new Color(128, 128, 128));
            UIManager.put("nimbusBase", new Color(18, 30, 49));
            UIManager.put("nimbusAlertYellow", new Color(248, 187, 0));
            UIManager.put("nimbusDisabledText", new Color(128, 128, 128));
            UIManager.put("nimbusFocus", new Color(115, 164, 209));
            UIManager.put("nimbusGreen", new Color(176, 179, 50));
            UIManager.put("nimbusInfoBlue", new Color(66, 139, 221));
            UIManager.put("nimbusLightBackground", new Color(18, 30, 49));
            UIManager.put("nimbusOrange", new Color(191, 98, 4));
            UIManager.put("nimbusRed", new Color(169, 46, 34));
            UIManager.put("nimbusSelectedText", new Color(255, 255, 255));
            UIManager.put("nimbusSelectionBackground", new Color(104, 93, 156));
            UIManager.put("text", new Color(230, 230, 230));
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    try {
                        UIManager.setLookAndFeel(info.getClassName());
                    } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e) {
                        e.printStackTrace();
                    }
                    break;
                }
            }
            new Pitchenation();
        });
    }

    @Override
    public void handlePitch(PitchDetectionResult pitchDetectionResult, AudioEvent audioEvent) {
        try {
            if (pitchDetectionResult.getPitch() != -1) {
                float pitch = pitchDetectionResult.getPitch();
                float probability = pitchDetectionResult.getProbability();
                double rms = audioEvent.getRMS() * 100;
                Pitch guess = matchPitch(pitch);
//            String guessEhroma = guess == null ? "" : guess.getEhroma();
//            String message = String.format("    %s %.2fHz - %.2f | %.5f", guessChroma, pitch, probability, rms);
//            out(message);
                if (guess != null) {
                    play(pitch, probability, rms, guess);
                }
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    public Pitchenation() {
        setLayout(new GridLayout(6, 1));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setTitle("Pitchenation");

        JPanel controlPanel = new JPanel();
        add(controlPanel);
        controlPanel.setLayout(new GridBagLayout());

        JButton startStopButton = new JButton();
        controlPanel.add(startStopButton);
        SwingUtilities.invokeLater(() -> startStopButton.setText("Start"));
        startStopButton.setAction(
                new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        updateStartStopButton(startStopButton);
                    }
                }
        );

        JPanel inputPanel = new InputPanel();
        add(inputPanel);
        inputPanel.addPropertyChangeListener("mixer", event -> {
            try {
                setNewMixer((Mixer) event.getNewValue());
            } catch (LineUnavailableException e) {
                e.printStackTrace();
            }
        });

        JPanel pitchAlgoPanel = new PitchAlgoPanel(event -> {
            String name = event.getActionCommand();
            algo = PitchEstimationAlgorithm.valueOf(name);
            try {
                setNewMixer(currentMixer);
            } catch (LineUnavailableException e) {
                e.printStackTrace();
            }
        });
        add(pitchAlgoPanel);

        riddlePanel = new JPanel();
        add(riddlePanel);
        riddleLabel = new JLabel();
        riddlePanel.add(riddleLabel);
        riddleLabel.setForeground(Color.WHITE);
        riddleLabel.setBackground(Color.BLACK);
        riddleLabel.setOpaque(true);

        guessPanel = new JPanel();
        add(guessPanel);
        JPanel labelsPanel = new JPanel();
        guessPanel.add(labelsPanel);
        labelsPanel.setOpaque(false);
        labelsPanel.setLayout(new GridLayout(2, 1));

        JPanel guessLabelPanel = new JPanel();
        labelsPanel.add(guessLabelPanel);
        guessLabelPanel.setOpaque(false);
        guessLabel = new JLabel();
        guessLabelPanel.add(guessLabel);
        guessLabel.setOpaque(true);
        guessLabel.setVisible(false);
        guessLabel.setText("    ");
        guessLabel.setForeground(Color.WHITE);
        guessLabel.setBackground(Color.BLACK);

        chromaPanel = new JPanel();
        labelsPanel.add(chromaPanel);
        chromaPanel.setPreferredSize(new Dimension(400, 20));
        chromaPanel.setSize(new Dimension(400, 20));
        chromaPanel.setOpaque(true);

        JPanel colorsPanel = new JPanel();
        add(colorsPanel);
        colorsPanel.setLayout(new GridLayout(1, 12));
        for (Map.Entry<String, Color> chromaColor : chromaToColor.entrySet()) {
            JPanel colorPanel = new JPanel();
            colorsPanel.add(colorPanel);
            colorPanel.setBackground(chromaColor.getValue());
            JLabel colorLabel = new JLabel(" " + chromaColor.getKey() + " ");
            colorPanel.add(colorLabel);
            colorLabel.setForeground(Color.WHITE);
            colorLabel.setBackground(Color.BLACK);
            colorLabel.setOpaque(true);
        }

        pack();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation(screen.width - getSize().width - 15, screen.height / 2 - getSize().height / 2);
        setVisible(true);

        executor.execute(() -> {
            player.play(playOnSuccess.getNote());
            if (isRunning.get()) {
                play(-1, 0, 0, null);
            }

            for (Mixer.Info info : Shared.getMixerInfo(false, true)) {
                if (info.toString().contains("Default")) {
                    Mixer newValue = AudioSystem.getMixer(info);
                    try {
                        setNewMixer(newValue);
                    } catch (LineUnavailableException e) {
                        e.printStackTrace();
                    }
                    break;
                }
            }
        });
    }

    private void updateStartStopButton(JButton startStopButton) {
        out("\nisRunning=" + isRunning.get());
        boolean updated = isRunning.compareAndSet(false, true);
        out("updatedToTrue=" + updated);
        if (updated) {
            Pitchenation.this.riddle.set(null);
            executor.execute(() -> play(-1, 0, 0, null));
            startStopButton.setText("Stop");
        } else {
            updated = isRunning.compareAndSet(true, false);
            out("updatedToFalse=" + updated);
            if (updated) {
                riddlePanel.setBackground(null);
                riddleLabel.setText("");
            } else {
                out("Weird - should not happen");
            }
        }
        if (updated) {
            startStopButton.setText(isRunning.get() ? "Stop" : "Start");
        }
        out("isRunning=" + isRunning.get());
    }

    private void setNewMixer(Mixer mixer) throws LineUnavailableException {
        if (dispatcher != null) {
            dispatcher.stop();
        }
        currentMixer = mixer;

        float sampleRate = 44100;
        int bufferSize = 1024;
        int overlap = 0;

        out("Started listening with " + Shared.toLocalString(mixer.getMixerInfo().getName()) + "\n");

        final AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, true);
        final DataLine.Info dataLineInfo = new DataLine.Info(TargetDataLine.class, format);
        TargetDataLine line;
        line = (TargetDataLine) mixer.getLine(dataLineInfo);
        line.open(format, bufferSize);
        line.start();
        final AudioInputStream stream = new AudioInputStream(line);

        JVMAudioInputStream audioStream = new JVMAudioInputStream(stream);
        dispatcher = new AudioDispatcher(audioStream, bufferSize, overlap);
        dispatcher.addAudioProcessor(new PitchProcessor(algo, sampleRate, bufferSize, this));

        new Thread(dispatcher, "Audio dispatching").start();
    }

    private void out(String message) {
        System.out.println(message);
    }

    public static class InputPanel extends JPanel {
        private static final long serialVersionUID = 1L;

        Mixer mixer = null;

        public InputPanel() {
            super(new BorderLayout());
            this.setBorder(new TitledBorder("Input"));
            JPanel buttonPanel = new JPanel(new GridLayout(0, 1));
            ButtonGroup group = new ButtonGroup();
            for (Mixer.Info info : Shared.getMixerInfo(false, true)) {
                JRadioButton button = new JRadioButton();
                button.setText(Shared.toLocalString(info));
                buttonPanel.add(button);
                group.add(button);
                button.setActionCommand(info.toString());
                button.addActionListener(event -> {
                    String command = event.getActionCommand();
                    setMixer(command);
                });
                if (info.toString().contains("Default")) {
                    button.setSelected(true);
                }
            }

            this.add(new JScrollPane(buttonPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
            this.setMaximumSize(new Dimension(400, 200));
            this.setPreferredSize(new Dimension(400, 200));
        }

        private void setMixer(String command) {
            for (Mixer.Info info : Shared.getMixerInfo(false, true)) {
                if (command.equals(info.toString())) {
                    Mixer newValue = AudioSystem.getMixer(info);
                    InputPanel.this.firePropertyChange("mixer", mixer, newValue);
                    InputPanel.this.mixer = newValue;
                    break;
                }
            }
        }

    }

    public static class PitchAlgoPanel extends JPanel {
        private static final long serialVersionUID = -5107785666165487335L;

        public PitchAlgoPanel(ActionListener algoChangedListener) {
            super(new GridLayout(0, 1));
            setBorder(new TitledBorder("Pitch detection algorithm"));
            ButtonGroup group = new ButtonGroup();
            for (PitchEstimationAlgorithm value : PitchEstimationAlgorithm.values()) {
                JRadioButton button = new JRadioButton();
                button.setText(value.toString());
                add(button);
                group.add(button);
                button.setSelected(value == defaultPitchAlgo);
                button.setActionCommand(value.name());
                button.addActionListener(algoChangedListener);
            }
        }
    }

}
