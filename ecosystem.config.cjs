module.exports = {
  apps: [{
    name: "politeai-backend",
    script: ".venv/bin/uvicorn",
    args: "app.main:app --reload --port 8080",
    cwd: "/home/sms02/PoliteAi",
    interpreter: "none",
    env: {
      ENVIRONMENT: "dev"
    },
    // Log settings
    error_file: "/home/sms02/.pm2/logs/politeai-backend-error.log",
    out_file: "/home/sms02/.pm2/logs/politeai-backend-out.log",
    merge_logs: true,
    log_date_format: "YYYY-MM-DD HH:mm:ss",
    // Restart settings
    max_restarts: 5,
    restart_delay: 1000,
    autorestart: true
  }]
};
