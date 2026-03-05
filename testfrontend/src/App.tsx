import { useEffect, useState } from 'react';
import axios from 'axios';
import {
  CurrencyDollarIcon,
  PhoneIcon,
  ClockIcon,
  ExclamationTriangleIcon,
} from '@heroicons/react/24/outline';
import {
  PieChart, Pie, Cell, Tooltip, Legend,
  BarChart, Bar, XAxis, YAxis, CartesianGrid, ResponsiveContainer
} from 'recharts';

interface DashboardData {
  totalCost: number;
  totalCalls: number;
  totalDurationSeconds: number;
  averageCostPerMinute: number;
  processingFailures: number;
  unassignedCalls: number;
  costByTelephonyType: { telephonyTypeName: string; cost: number }[];
  topCostCenters: { costCenterName: string; cost: number }[];
}

const COLORS = ['#0088FE', '#00C49F', '#FFBB28', '#FF8042', '#8884D8'];

export default function Dashboard() {
  const [data, setData] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Default to current month for the prototype using local time
  const now = new Date();
  const formatToLocalISO = (date: Date) => {
    const pad = (n: number) => n.toString().padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
  };

  const [selectedMonth, setSelectedMonth] = useState(`${now.getFullYear()}-${(now.getMonth() + 1).toString().padStart(2, '0')}`);

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        setError(null);

        const [yearStr, monthStr] = selectedMonth.split('-');
        const year = parseInt(yearStr);
        const month = parseInt(monthStr) - 1; // 0-indexed

        const startOfMonth = formatToLocalISO(new Date(year, month, 1));
        const endOfMonth = formatToLocalISO(new Date(year, month + 1, 0, 23, 59, 59));
        // Using a proxy to avoid CORS issues in dev, or relative path
        const response = await axios.get('/api/dashboard/overview', {
          params: { startDate: startOfMonth, endDate: endOfMonth },
          // Mock auth for the prototype
          headers: {
            'X-Tenant-Id': 'colsanitas',
            'X-Username': 'admin'
          }
        });
        setData(response.data);
      } catch (err: any) {
        console.error("API failed", err);
        setError("Failed to fetch dashboard data.");
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [selectedMonth]);

  if (loading) return <div className="p-10 text-center text-gray-500 font-semibold text-lg animate-pulse">Loading Dashboard Data...</div>;
  if (!data) return <div className="p-10 text-center text-red-500">Error loading data</div>;

  return (
    <div className="min-h-screen bg-gray-50 text-gray-900 p-8 font-sans">
      <div className="max-w-7xl mx-auto">

        {/* Header */}
        <div className="flex flex-col md:flex-row justify-between items-start md:items-center mb-8 gap-4">
          <div>
            <h1 className="text-3xl font-bold tracking-tight text-gray-900">Telephony Dashboard</h1>
            <p className="mt-2 text-sm text-gray-600">Overview for selected period</p>
          </div>
          <div className="flex items-center space-x-4">
            <div className="flex items-center space-x-2 bg-white px-4 py-2 rounded-lg border border-gray-200 shadow-sm">
              <label htmlFor="month" className="text-sm font-medium text-gray-600">Month:</label>
              <input
                type="month"
                id="month"
                value={selectedMonth}
                onChange={(e) => setSelectedMonth(e.target.value)}
                className="text-gray-900 bg-transparent border-none focus:ring-0 text-sm font-semibold outline-none"
              />
            </div>

            {data.processingFailures > 0 && (
              <div className="flex items-center space-x-2 bg-red-100 text-red-700 px-4 py-2 rounded-lg font-medium shadow-sm">
                <ExclamationTriangleIcon className="h-5 w-5" />
                <span>{data.processingFailures} processing failures detected</span>
              </div>
            )}
          </div>
        </div>

        {error && (
          <div className="mb-8 bg-red-50 text-red-600 p-4 rounded-xl border border-red-200">
            {error}
          </div>
        )}

        {/* KPI Cards */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-8">
          <StatCard
            title="Total Cost"
            value={`$${data.totalCost.toFixed(2)}`}
            icon={<CurrencyDollarIcon className="h-6 w-6 text-emerald-600" />}
          />
          <StatCard
            title="Total Calls"
            value={data.totalCalls.toLocaleString()}
            icon={<PhoneIcon className="h-6 w-6 text-blue-600" />}
          />
          <StatCard
            title="Total Duration"
            value={`${Math.round(data.totalDurationSeconds / 3600)} hrs`}
            icon={<ClockIcon className="h-6 w-6 text-purple-600" />}
          />
          <StatCard
            title="Avg Cost / Min"
            value={`$${data.averageCostPerMinute.toFixed(4)}`}
            icon={<CurrencyDollarIcon className="h-6 w-6 text-amber-600" />}
          />
        </div>

        {/* Charts Row */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 mb-8">

          {/* Donut Chart */}
          <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-100">
            <h3 className="text-lg font-semibold mb-4 border-b pb-2">Cost by Call Type</h3>
            <div className="h-72">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={data.costByTelephonyType}
                    cx="50%"
                    cy="50%"
                    innerRadius={60}
                    outerRadius={90}
                    paddingAngle={5}
                    dataKey="cost"
                    nameKey="telephonyTypeName"
                  >
                    {data.costByTelephonyType.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip formatter={(value: number) => `$${value.toFixed(2)}`} />
                  <Legend verticalAlign="bottom" height={36} />
                </PieChart>
              </ResponsiveContainer>
            </div>
          </div>

          {/* Bar Chart */}
          <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-100">
            <h3 className="text-lg font-semibold mb-4 border-b pb-2">Top Cost Centers</h3>
            <div className="h-72">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={data.topCostCenters} layout="vertical" margin={{ top: 5, right: 30, left: 40, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" horizontal={false} />
                  <XAxis type="number" tickFormatter={(opt) => `$${opt}`} />
                  <YAxis type="category" dataKey="costCenterName" />
                  <Tooltip formatter={(value: number) => `$${value.toFixed(2)}`} />
                  <Bar dataKey="cost" fill="#3b82f6" radius={[0, 4, 4, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>

        </div>

      </div>
    </div>
  );
}

function StatCard({ title, value, icon }: { title: string, value: string, icon: React.ReactNode }) {
  return (
    <div className="bg-white rounded-xl shadow-sm p-6 flex items-center space-x-4 border border-gray-100 hover:shadow-md transition-shadow">
      <div className="p-3 bg-gray-50 rounded-full">
        {icon}
      </div>
      <div>
        <p className="text-sm font-medium text-gray-500">{title}</p>
        <p className="text-2xl font-bold text-gray-900">{value}</p>
      </div>
    </div>
  );
}
