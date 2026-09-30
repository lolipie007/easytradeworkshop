using System.Reflection;
using EasyTrade.BrokerService;
using EasyTrade.BrokerService.Helpers;
using EasyTrade.BrokerService.Helpers.Logging;
using EasyTrade.BrokerService.Middleware.CreditCardValidation;
using EasyTrade.BrokerService.ProblemPatterns.HighCpuUsage;
using Microsoft.EntityFrameworkCore;
using Microsoft.OpenApi.Models;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.
builder.Services.AddControllers();

// Default CORS policy allowing every connection
builder.Services.AddCors(services =>
    services.AddDefaultPolicy(policy => policy.AllowAnyHeader().AllowAnyMethod().AllowAnyOrigin())
);

// Learn more about configuring Swagger/OpenAPI at https://aka.ms/aspnetcore/swashbuckle
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen(options =>
{
    options.SwaggerDoc("v1", new OpenApiInfo { Version = "v1", Title = "EasyTradeBrokerService" });
    // Turn on generating Swagger documentation from comments
    var xmlDocumentation = $"{Assembly.GetExecutingAssembly().GetName().Name}.xml";
    options.IncludeXmlComments(Path.Combine(AppContext.BaseDirectory, xmlDocumentation));
});

// Connect to database.
// broker-service issues ~2.4 DB calls per request and is the first service to saturate under
// load (Bluebox capacity analysis). ADO.NET SqlClient defaults to Max Pool Size = 100, which
// becomes the bottleneck well before CPU at high traffic. Make the pool size explicit and
// env-tunable so it can be scaled without a code change.
builder.Services.AddDbContext<BrokerDbContext>(options =>
    options.UseSqlServer(
        DatabaseConnection.WithPoolSize(
            builder.Configuration[Constants.MsSqlConnectionString],
            builder.Configuration[Constants.DbMaxPoolSize]
        )
    )
);

// Clear default logging providers and and new ones
builder.Logging.ClearProviders();
builder.Logging.AddCustomLogger(options =>
{
    options.SkipString = "EasyTrade.BrokerService.";
    options.MinimumMessageLength = 100;
});

// Add dependency injection group
builder.Services.AddBrokerServiceDependencyGroup();
builder.Services.AddTransient<HighCpuUsageMiddleware>();

// Add HTTP client used to connect to other services
builder.Services.AddHttpClient();

var app = builder.Build();

// Configure the HTTP request pipeline.
if (app.Environment.IsDevelopment())
{
    app.UseDeveloperExceptionPage();

    // Add and configure Swagger
    var proxyPrefix = builder.Configuration[Constants.ProxyPrefix] ?? string.Empty;
    app.UseSwagger(options =>
    {
        if (!string.IsNullOrEmpty(proxyPrefix))
        {
            options.PreSerializeFilters.Add(
                (swagger, request) =>
                {
                    var url = $"{request.Scheme}://{request.Host}/{proxyPrefix}";
                    swagger.Servers = [new() { Url = url }];
                }
            );
        }
    });
    app.UseSwaggerUI(options =>
        options.SwaggerEndpoint("v1/swagger.json", "EasyTradeBrokerService v1")
    );
}

app.UseHttpsRedirection();

app.UseAuthorization();

app.UseMiddleware<HighCpuUsageMiddleware>();
app.UseMiddleware<CreditCardValidationMiddleware>();

app.MapControllers();

app.Run();
