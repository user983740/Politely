export default function HomePage() {
  return (
    <div className='min-h-screen bg-bg flex items-center justify-center'>
      <div className='w-full max-w-2xl px-4'>
        <h1 className='text-2xl font-semibold text-text mb-2'>PoliteAi</h1>
        <p className='text-text-secondary mb-8'>
          보내기 직전, 말투를 안전하게 다듬어 드립니다!
        </p>
        {/* TransformPanel widget will be placed here */}
      </div>
    </div>
  );
}
