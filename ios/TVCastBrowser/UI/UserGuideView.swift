import SwiftUI

struct UserGuideView: View {
    @Environment(\.dismiss) private var dismiss
    var onDismiss: () -> Void

    @State private var currentStep: Int = 0

    private struct GuideStepItem: Identifiable {
        let id: Int
        let iconName: String
        let titleKey: String
        let descKey: String
    }

    private let steps: [GuideStepItem] = [
        GuideStepItem(
            id: 0,
            iconName: "safari.fill",
            titleKey: "user_guide_step1_title",
            descKey: "user_guide_step1_desc"
        ),
        GuideStepItem(
            id: 1,
            iconName: "shield.fill",
            titleKey: "user_guide_step2_title",
            descKey: "user_guide_step2_desc"
        ),
        GuideStepItem(
            id: 2,
            iconName: "play.circle.fill",
            titleKey: "user_guide_step3_title",
            descKey: "user_guide_step3_desc"
        ),
        GuideStepItem(
            id: 3,
            iconName: "tv.fill",
            titleKey: "user_guide_step4_title",
            descKey: "user_guide_step4_desc"
        )
    ]

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                // Header Step Counter
                Text(String(format: NSLocalizedString("user_guide_step_counter", comment: ""), currentStep + 1, steps.count))
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.accentColor)
                    .padding(.top, 12)

                // Pager
                TabView(selection: $currentStep) {
                    ForEach(steps) { step in
                        VStack(spacing: 24) {
                            Spacer()
                            ZStack {
                                Circle()
                                    .fill(Color.accentColor.opacity(0.15))
                                    .frame(width: 100, height: 100)
                                Image(systemName: step.iconName)
                                    .font(.system(size: 46))
                                    .foregroundColor(.accentColor)
                            }

                            Text(NSLocalizedString(step.titleKey, comment: ""))
                                .font(.title2.weight(.bold))
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 20)

                            Text(NSLocalizedString(step.descKey, comment: ""))
                                .font(.body)
                                .foregroundColor(.secondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 28)
                            Spacer()
                        }
                        .tag(step.id)
                    }
                }
                .tabViewStyle(PageTabViewStyle(indexDisplayMode: .always))

                // Bottom Controls
                HStack {
                    if currentStep > 0 {
                        Button(NSLocalizedString("user_guide_back", comment: "")) {
                            withAnimation {
                                currentStep -= 1
                            }
                        }
                        .buttonStyle(.bordered)
                    } else {
                        Button(NSLocalizedString("user_guide_skip", comment: "")) {
                            onDismiss()
                            dismiss()
                        }
                        .foregroundColor(.secondary)
                    }

                    Spacer()

                    Button {
                        if currentStep == steps.count - 1 {
                            onDismiss()
                            dismiss()
                        } else {
                            withAnimation {
                                currentStep += 1
                            }
                        }
                    } label: {
                        Text(currentStep == steps.count - 1 ? NSLocalizedString("user_guide_start", comment: "") : NSLocalizedString("user_guide_next", comment: ""))
                            .fontWeight(.semibold)
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 24)
            }
            .navigationTitle(NSLocalizedString("user_guide_title", comment: ""))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        onDismiss()
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                    }
                }
            }
        }
    }
}

